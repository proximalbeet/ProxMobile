#!/usr/bin/env python3
"""The phone's /voice server: a thin relay to ProxAI's voice door (VOICE-RELAY, 2026-11-17).

Prox speaks, the app relays. The phone sends reply text here (Tailscale Serve publishes this at
https://<desktop>.ts.net/voice); this passes the phone's own Bearer header and the text, unchanged,
to the gateway's voice door, a Unix socket at ~/.local/state/proxai/voice/api/voice.sock, and hands
the door's mp3 back. The door checks the key and makes the voice; this server holds no key, reads
no key file, no state folder and no model, and never opens a network socket: systemd's
proxai-phone-voice.socket hands it the listener (127.0.0.1:8653) and the unit has no network.

  GET  /health      no token: ok, and whether the door answers
  GET  /v1/mode     the door's /mode (the PC's server/personal mode + its local model)
  POST /v1/warmup   202, does nothing (the door keeps the voice ready)
  POST /v1/tts      {"text": "..."} -> the door's /speak {"text", "which": "app"} -> audio/mpeg
                    ("voice" and any other field from the phone are ignored)

Every route but /health needs "Authorization: Bearer <1-200 visible chars>"; without one: 401 here,
and the door is never called. The door's status and JSON error come back as they are. If the door
is missing or gives no answer in 70 s: 503 "ProxAI isn't running".

Standard library only; run as /usr/bin/python3 -I. After PROXAI_VOICE_IDLE seconds with no
request it exits; systemd holds the port and starts it again on the next request.
"""
from __future__ import annotations

import http.client
import json
import os
import re
import socket
import socketserver
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_DOOR = "~/.local/state/proxai/voice/api/voice.sock"
IDLE_SECONDS = int(os.environ.get("PROXAI_VOICE_IDLE", "300"))
DOOR_TIMEOUT = 70.0       # seconds the door has to answer /speak or /mode
HEALTH_TIMEOUT = 3.0      # /health only asks whether the door is there
MAX_BODY = 16 * 1024      # request body from the phone
MAX_TEXT_CHARS = 1200
MAX_AUDIO = 8 * 1024 * 1024    # ~10 min of speech at 128 kbit/s; a 1200-char piece is ~1.5 MB
MAX_DOOR_JSON = 64 * 1024
# The phone's header, as the door expects it: "Bearer " and 1-200 visible ASCII chars (no spaces).
BEARER = re.compile(r"Bearer [\x21-\x7e]{1,200}")
NOT_RUNNING = "ProxAI isn't running"
DOOR_WHICH = "app"  # who is asking the door to speak: always the phone app, never from the request
ROUTES = ("/health", "/v1/health", "/v1/mode", "/v1/warmup", "/v1/tts")


def door_path() -> str:
    return os.path.expanduser(os.environ.get("PROXAI_VOICE_DOOR") or DEFAULT_DOOR)


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class DoorConnection(http.client.HTTPConnection):
    """HTTP over the door's Unix socket."""

    def __init__(self, path: str, timeout: float):
        super().__init__("localhost", timeout=timeout)
        self.unix_path = path

    def connect(self):
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.settimeout(self.timeout)
            sock.connect(self.unix_path)
        except BaseException:
            sock.close()
            raise
        self.sock = sock


class DoorError(Exception):
    def __init__(self, status: int, error: str):
        super().__init__(error)
        self.status, self.error = status, error


def ask_door(method: str, path: str, auth: str | None, body: bytes | None = None,
             timeout: float | None = None, limit: int = MAX_DOOR_JSON) -> tuple[int, str, bytes]:
    """One request to the door. Returns (status, content type, body); raises DoorError(503) when
    the door is missing or silent, DoorError(502) when its answer is broken or too big."""
    conn = DoorConnection(door_path(), DOOR_TIMEOUT if timeout is None else timeout)
    headers = {"Host": "localhost"}
    if auth is not None:
        headers["Authorization"] = auth
    if body is not None:
        headers["Content-Type"] = "application/json"
        headers["Content-Length"] = str(len(body))
    try:
        conn.request(method, path, body=body, headers=headers)
        resp = conn.getresponse()
        data = resp.read(limit + 1)
        if len(data) > limit:
            raise DoorError(502, "ProxAI's answer was too big")
        return resp.status, resp.getheader("Content-Type", "") or "", data
    except (OSError, TimeoutError):
        raise DoorError(503, NOT_RUNNING) from None
    except http.client.HTTPException:
        raise DoorError(502, "ProxAI's answer was broken") from None
    finally:
        conn.close()


def door_error_text(status: int, data: bytes) -> str:
    """The door's own JSON error, or a plain line when it sent none."""
    try:
        err = json.loads(data).get("error")
        if isinstance(err, str) and err.strip():
            return err.strip()[:300]
    except (ValueError, AttributeError):
        pass
    return f"ProxAI answered HTTP {status}"


class Activity:
    """Requests in flight and the last time one finished, for the idle exit."""

    def __init__(self):
        self.lock = threading.Lock()
        self.busy = 0
        self.last = time.monotonic()

    def begin(self):
        with self.lock:
            self.busy += 1

    def end(self):
        with self.lock:
            self.busy -= 1
            self.last = time.monotonic()

    def idle_for(self) -> float:
        with self.lock:
            return 0.0 if self.busy else time.monotonic() - self.last


ACTIVITY = Activity()


class Handler(BaseHTTPRequestHandler):
    server_version = "ProxAIVoiceRelay/2"
    timeout = 30  # a phone that stops sending mid-request doesn't hold a thread for long

    def log_message(self, fmt, *args):  # no default access log: it would print paths and addresses
        pass

    @property
    def route(self) -> str:
        # Tailscale Serve may or may not strip the /voice mount; accept both.
        path = self.path.split("?", 1)[0]
        return path[len("/voice"):] if path.startswith("/voice/") or path == "/voice" else path

    def _send(self, code: int, ctype: str, data: bytes):
        self.status = code
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _json(self, code: int, body: dict):
        self._send(code, "application/json", json.dumps(body).encode())

    def _auth(self) -> str | None:
        """The phone's Authorization header if it is a well-formed Bearer header, else None."""
        value = self.headers.get("Authorization")
        return value if value is not None and BEARER.fullmatch(value) else None

    def _handle(self, method: str):
        t = time.monotonic()
        self.status = 0
        ACTIVITY.begin()
        try:
            self._dispatch(method)
        except DoorError as e:
            self._json(e.status, {"error": e.error})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass  # the phone went away; nothing to answer
        finally:
            ACTIVITY.end()
            route = self.route if self.route in ROUTES else "other"
            # Route, status and time only: never the text, the token or the caller's address.
            log(f"{method} {route} {self.status} {int((time.monotonic() - t) * 1000)}ms")

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def _dispatch(self, method: str):
        route = self.route
        if method == "GET" and route in ("/health", "/v1/health"):
            return self._health()
        if (method, route) not in (("GET", "/v1/mode"), ("POST", "/v1/warmup"), ("POST", "/v1/tts")):
            return self._json(404, {"error": "not found"})
        auth = self._auth()
        if auth is None:
            return self._json(401, {"error": "missing or bad API key"})
        if route == "/v1/mode":
            return self._mode(auth)
        if route == "/v1/warmup":
            return self._json(202, {})
        return self._tts(auth)

    def _health(self):
        try:
            status, _, _ = ask_door("GET", "/health", None, timeout=HEALTH_TIMEOUT)
            door = status == 200
        except DoorError:
            door = False
        self._json(200, {"status": "ok", "door": door})

    def _mode(self, auth: str):
        status, _, data = ask_door("GET", "/mode", auth)
        if status != 200:
            return self._pass_error(status, data)
        try:
            body = json.loads(data)
        except ValueError:
            body = None
        if not isinstance(body, dict):
            return self._json(502, {"error": "ProxAI's answer was broken"})
        self._json(200, body)

    def _tts(self, auth: str):
        text, problem = self._parse_body()
        if problem:
            return self._json(*problem)
        # Only the text comes from the phone. "which" is fixed: the door's /speak answers 400 without
        # which="app" (VOICE-DOOR door.py speak()); the phone's "voice" and any other field are dropped.
        payload = json.dumps({"text": text, "which": DOOR_WHICH}).encode()
        status, ctype, data = ask_door("POST", "/speak", auth, payload, limit=MAX_AUDIO)
        if status != 200:
            return self._pass_error(status, data)
        if ctype.split(";", 1)[0].strip().lower() != "audio/mpeg" or not data:
            return self._json(502, {"error": "ProxAI's answer wasn't audio"})
        self._send(200, "audio/mpeg", data)

    def _parse_body(self) -> tuple[str, tuple[int, dict] | None]:
        if self.headers.get("Transfer-Encoding"):
            return "", (411, {"error": "send a Content-Length"})
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return "", (411, {"error": "send a Content-Length"})
        if length < 0:
            return "", (400, {"error": "bad Content-Length"})
        if length > MAX_BODY:
            self.close_connection = True  # the body stays unread
            return "", (413, {"error": f"body over {MAX_BODY} bytes"})
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return "", (400, {"error": "body must be JSON"})
        text = body.get("text") if isinstance(body, dict) else None
        if not isinstance(text, str) or not text.strip():
            return "", (400, {"error": "empty text"})
        if len(text) > MAX_TEXT_CHARS:
            return "", (400, {"error": f"text over {MAX_TEXT_CHARS} chars; send it in pieces"})
        return text, None

    def _pass_error(self, status: int, data: bytes):
        # The door's 4xx/5xx and its error come back as they are; anything else is a broken answer.
        if not 400 <= status <= 599:
            return self._json(502, {"error": f"ProxAI answered HTTP {status}"})
        self._json(status, {"error": door_error_text(status, data)})


class RelayServer(ThreadingHTTPServer):
    """An HTTP server on a socket it was handed. TCPServer.__init__ would create a new IPv4
    socket, which RestrictAddressFamilies=AF_UNIX forbids, so this never calls it."""

    daemon_threads = False  # server_close() waits for replies in flight

    def __init__(self, sock: socket.socket):
        socketserver.BaseServer.__init__(self, sock.getsockname(), Handler)
        self.socket = sock
        host, port = sock.getsockname()[:2]
        self.server_name, self.server_port = host, port


def listener_from_systemd() -> socket.socket | None:
    """The socket systemd passes in (fd 3), or None when it passed none."""
    if os.environ.get("LISTEN_PID") != str(os.getpid()) or os.environ.get("LISTEN_FDS") != "1":
        return None
    return socket.socket(fileno=3)


def idle_watcher(server: RelayServer, idle_seconds: float, tick: float = 15.0):
    while ACTIVITY.idle_for() < idle_seconds:
        time.sleep(tick)
    log(f"idle {idle_seconds:.0f}s, exiting (the next request starts it again)")
    server.shutdown()  # stop accepting; new connections wait in systemd's socket


def main() -> int:
    sock = listener_from_systemd()
    if sock is None:
        print("voice_server: no listener from systemd (LISTEN_FDS); it never opens one itself. "
              "Start it through proxai-phone-voice.socket.", file=sys.stderr, flush=True)
        return 2
    server = RelayServer(sock)
    threading.Thread(target=idle_watcher, args=(server, IDLE_SECONDS), daemon=True).start()
    log(f"voice relay on {server.server_name}:{server.server_port} -> {door_path()}")
    server.serve_forever()
    server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
