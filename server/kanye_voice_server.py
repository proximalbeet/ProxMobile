#!/usr/bin/env python3
"""Kanye voice for the ProxAI phone app.

The phone sends reply text, this answers with a WAV in the same `kanye` voice Hermes uses on
Discord. It reuses Hermes's own voice script (tts_pipeline.py: reference clip, delivery settings,
Ollama unloading), so the voice is identical.

Unlike Hermes's one-shot command, the model stays loaded between replies (about 1 s per sentence
instead of about 8 s cold). After PROXAI_VOICE_IDLE seconds idle the whole process exits, which
gives back both the ~3.4 GB of VRAM (to Ollama) and the RAM PyTorch keeps after a model unload.
systemd holds the port (proxai-phone-voice.socket) and starts this again on the next request.

Runs with Hermes's Chatterbox venv, listening on 127.0.0.1 only; Tailscale Serve publishes it at
https://<desktop>.ts.net/voice. Requests need the proxai API_SERVER_KEY as a Bearer token.

  GET  /health          status (no auth)
  GET  /v1/mode         the PC's server/personal mode + its local model (for the app's model choice)
  POST /v1/warmup       start loading the model in the background (202)
  POST /v1/tts          {"text": "...", "voice": "kanye"} -> audio/wav
"""
from __future__ import annotations

import hmac
import io
import json
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VOICE_DIR = Path(os.getenv("PROXAI_VOICE_DIR", "~/.hermes/profiles/proxai/voice")).expanduser()
ENV_FILE = Path(os.getenv("PROXAI_ENV_FILE", "~/.hermes/profiles/proxai/.env")).expanduser()
HOST = os.getenv("PROXAI_VOICE_HOST", "127.0.0.1")
PORT = int(os.getenv("PROXAI_VOICE_PORT", "8653"))
IDLE_SECONDS = int(os.getenv("PROXAI_VOICE_IDLE", "300"))
MAX_TEXT_CHARS = 1200
VOICE_NAME = re.compile(r"^[a-z0-9_-]{1,40}$")
# reference/ also holds raw source recordings, so only listed clips count as voices.
ALLOWED_VOICES = [v.strip() for v in os.getenv("PROXAI_VOICES", "kanye").split(",") if v.strip()]
# Out of GPU memory on a piece: halve it and retry, down to this size (10-22: in server mode the
# local model holds ~10.2 GB, so a 215-char piece needed 382 MB more than was free).
SMALLEST_PIECE = 60

# Lets PyTorch hand freed VRAM back in smaller blocks (less waste when memory is tight).
# Must be set before torch loads.
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

sys.path.insert(0, str(VOICE_DIR))
import tts_pipeline as pipeline  # noqa: E402  (Hermes's script: settings + helpers)


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class ApiKey:
    """The proxai API_SERVER_KEY, re-read when the .env changes (so a rotated key just works)."""

    def __init__(self, path: Path):
        self.path, self._mtime, self._key = path, None, ""

    def get(self) -> str:
        try:
            mtime = self.path.stat().st_mtime
        except OSError:
            return ""
        if mtime != self._mtime:
            self._key, self._mtime = self._read(), mtime
        return self._key

    def _read(self) -> str:
        # dotenv rules: quoted value as-is; unquoted value stops at a " #" comment.
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if line.startswith("API_SERVER_KEY="):
                v = line.split("=", 1)[1].strip()
                if v[:1] in "\"'" and v[-1:] == v[:1]:
                    return v[1:-1]
                return re.split(r"\s+#", v, maxsplit=1)[0].strip()
        return ""


class Voice:
    """Chatterbox kept warm on the GPU, one generation at a time."""

    def __init__(self):
        self.lock = threading.Lock()
        self.model = None
        self.prepared_voice = None
        self.last_used = time.monotonic()
        self.loading = False

    def _load(self):
        from chatterbox.tts import ChatterboxTTS
        t = time.monotonic()
        pipeline.free_ollama_vram()  # same as Hermes: make room or Chatterbox runs out of VRAM
        self.model = ChatterboxTTS.from_pretrained(device="cuda")
        log(f"model loaded in {time.monotonic() - t:.1f}s")

    def _prepare(self, voice: str):
        if self.prepared_voice != voice:
            ref = VOICE_DIR / "reference" / f"{voice}.wav"
            self.model.prepare_conditionals(str(ref), exaggeration=pipeline.EXAGGERATION)
            self.prepared_voice = voice

    def warmup(self, voice: str = "kanye"):
        with self.lock:
            self.last_used = time.monotonic()
            if self.model is None:
                self.loading = True
                try:
                    self._load()
                finally:
                    self.loading = False
            self._prepare(voice)

    def speak(self, text: str, voice: str) -> tuple[bytes, float]:
        import numpy as np
        import soundfile as sf
        self.warmup(voice)
        with self.lock:
            gap = np.zeros(int(pipeline.GAP_SECONDS * self.model.sr), dtype=np.float32)
            pieces = []
            for chunk in pipeline.split_into_chunks(text):
                for wav in self._generate(chunk):
                    pieces += [wav.squeeze().cpu().numpy(), gap]
            audio = np.concatenate(pieces[:-1])
            buf = io.BytesIO()
            sf.write(buf, audio, self.model.sr, format="WAV", subtype="PCM_16")
            self.last_used = time.monotonic()
            return buf.getvalue(), len(audio) / self.model.sr

    def _generate(self, text: str) -> list:
        """One piece of speech. If the GPU runs out of memory, free PyTorch's cache and retry the
        text in two halves (smaller pieces need less memory), down to SMALLEST_PIECE chars."""
        import torch
        try:
            return [self.model.generate(text, exaggeration=pipeline.EXAGGERATION,
                                        cfg_weight=pipeline.CFG_WEIGHT)]
        except torch.cuda.OutOfMemoryError:
            torch.cuda.empty_cache()
            if len(text) <= SMALLEST_PIECE:
                raise
            halves = split_half(text)
            log(f"out of GPU memory on {len(text)} chars; retrying as {len(halves[0])} + {len(halves[1])}")
            return [w for half in halves for w in self._generate(half)]

    def is_idle(self) -> bool:
        # Holding the lock means no generation or model load is running right now.
        with self.lock:
            return not self.loading and time.monotonic() - self.last_used >= IDLE_SECONDS

    def voices(self) -> list[str]:
        """Voices the phone may ask for: the allow-list, if its reference clip exists."""
        return [v for v in ALLOWED_VOICES if VOICE_NAME.match(v) and (VOICE_DIR / "reference" / f"{v}.wav").is_file()]


VOICE = Voice()
KEY = ApiKey(ENV_FILE)
MODE_FILE = Path(os.getenv("PROXAI_STATE_DIR", "~/.local/state/proxai")).expanduser() / "mode.json"


def split_half(text: str) -> tuple[str, str]:
    """Split near the middle, preferring a comma/semicolon, else a space."""
    mid = len(text) // 2
    for marks in ((",", ";", ":", " -"), (" ",)):
        cuts = [i + 1 for i, ch in enumerate(text) if any(text.startswith(m, i) for m in marks)]
        cuts = [c for c in cuts if len(text) // 4 <= c <= len(text) * 3 // 4]
        if cuts:
            cut = min(cuts, key=lambda c: abs(c - mid))
            return text[:cut].strip(), text[cut:].strip()
    return text[:mid], text[mid:]


def read_mode() -> dict:
    """Just the mode, when it was set, and the local model it uses (never the rest of mode.json)."""
    try:
        state = json.loads(MODE_FILE.read_text())
    except (OSError, ValueError):
        return {"mode": "unknown", "local_model": None, "since": None}
    tier1 = (state.get("hermes") or {}).get("tier1") or {}
    return {"mode": state.get("mode") or "unknown", "since": state.get("since"),
            "local_model": tier1.get("model") if isinstance(tier1, dict) else None}


class Handler(BaseHTTPRequestHandler):
    server_version = "ProxAIVoice/1"

    def log_message(self, fmt, *args):  # quiet default access log; we log our own lines
        pass

    @property
    def route(self) -> str:
        # Tailscale Serve may or may not strip the /voice mount; accept both.
        path = self.path.split("?", 1)[0]
        return path[len("/voice"):] if path.startswith("/voice/") or path == "/voice" else path

    def _json(self, code: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self) -> bool:
        key = KEY.get()
        given = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        return bool(key) and hmac.compare_digest(given.encode(), key.encode())

    def do_GET(self):
        if self.route == "/v1/mode":
            # The app uses the PC's local model while it's in SERVER mode (Phase 10 mode.json).
            if not self._authorized():
                return self._json(401, {"error": "invalid API key"})
            return self._json(200, read_mode())
        if self.route in ("/health", "/v1/health"):
            return self._json(200, {"status": "ok", "loaded": VOICE.model is not None,
                                    "loading": VOICE.loading, "voices": VOICE.voices(),
                                    "idle_exit_seconds": IDLE_SECONDS})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.route not in ("/v1/tts", "/v1/warmup"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            log(f"rejected request from {self.client_address[0]} (bad key)")
            return self._json(401, {"error": "invalid API key"})
        try:
            length = min(int(self.headers.get("Content-Length", "0")), 64_000)
            body = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            return self._json(400, {"error": "body must be JSON"})
        voice = str(body.get("voice") or "kanye")
        if not VOICE_NAME.match(voice) or voice not in VOICE.voices():
            return self._json(400, {"error": f"unknown voice {voice!r}"})

        if self.route == "/v1/warmup":
            if VOICE.model is None and not VOICE.loading:
                threading.Thread(target=VOICE.warmup, args=(voice,), daemon=True).start()
            return self._json(202, {"loaded": VOICE.model is not None})

        text = " ".join(str(body.get("text") or "").split())
        if not text:
            return self._json(400, {"error": "empty text"})
        if len(text) > MAX_TEXT_CHARS:
            return self._json(413, {"error": f"text over {MAX_TEXT_CHARS} chars; send it in pieces"})
        try:
            t = time.monotonic()
            wav, seconds = VOICE.speak(text, voice)
        except Exception as exc:  # GPU out of memory, model download failure, ...
            log(f"tts failed: {exc!r}")
            return self._json(503, {"error": f"voice unavailable: {exc}"})
        log(f"tts {len(text)} chars -> {seconds:.1f}s audio in {time.monotonic() - t:.1f}s")
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(wav)))
        self.end_headers()
        self.wfile.write(wav)


def idle_watcher(server: ThreadingHTTPServer):
    while not VOICE.is_idle():
        time.sleep(15)
    log(f"idle {IDLE_SECONDS}s, exiting (frees VRAM and RAM; the next request starts it again)")
    server.shutdown()  # stop accepting; new connections wait in systemd's socket


def make_server() -> tuple[ThreadingHTTPServer, str]:
    """Use the socket systemd passes in (socket activation), or bind one when run by hand."""
    if os.getenv("LISTEN_PID") == str(os.getpid()) and os.getenv("LISTEN_FDS") == "1":
        server = ThreadingHTTPServer((HOST, PORT), Handler, bind_and_activate=False)
        server.socket.close()
        server.socket = socket.socket(fileno=3)
        host, port = server.socket.getsockname()[:2]
        server.server_name, server.server_port = host, port
        return server, f"http://{host}:{port} (socket from systemd)"
    return ThreadingHTTPServer((HOST, PORT), Handler), f"http://{HOST}:{PORT}"


def main():
    if not KEY.get():
        sys.exit(f"No API_SERVER_KEY in {ENV_FILE}")
    server, where = make_server()
    # Track handler threads so server_close() lets in-flight replies finish before exiting.
    server.daemon_threads = False
    threading.Thread(target=idle_watcher, args=(server,), daemon=True).start()
    log(f"Kanye voice listening on {where} (voices: {', '.join(VOICE.voices())})")
    server.serve_forever()
    server.server_close()


if __name__ == "__main__":
    main()
