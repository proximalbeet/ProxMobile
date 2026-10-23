#!/usr/bin/env python3
"""ProxAI apps server: lets the phone list the apps ProxAI built and download their APKs.

Read-only. It serves what Claude Code + proxai-app-build left in ~/dev/proxai/apps/<id>/
(status.json, builds.json, dist/*.apk). Hermes's `proxai_app` tool starts builds; this only shows
them. Listens on 127.0.0.1:8654; Tailscale Serve publishes it at https://<desktop>.ts.net/apps
(tailnet only). Every request except /health needs the proxai API_SERVER_KEY as a Bearer token.
Runs with the system python3 (standard library only).

  GET /health                          {"ok": true}
  GET /v1/apps                         every app: name, state, progress message, latest build
  GET /v1/apps/<id>                    one app + all its builds
  GET /v1/apps/<id>/apk                latest APK      (?code=N for an older build)
  GET /v1/apps/<id>/icon               (not yet)
"""
from __future__ import annotations

import hmac
import json
import os
import re
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

APPS = Path(os.getenv("PROXAI_APPS_DIR", "~/dev/proxai/apps")).expanduser()
ENV_FILE = Path(os.getenv("PROXAI_ENV_FILE", "~/.hermes/profiles/proxai/.env")).expanduser()
HOST = os.getenv("PROXAI_APPS_HOST", "127.0.0.1")
PORT = int(os.getenv("PROXAI_APPS_PORT", "8654"))
ID_RE = re.compile(r"^[a-z][a-z0-9-]{1,39}$")
APK_RE = re.compile(r"^[a-z][a-z0-9-]{1,39}-[0-9][0-9A-Za-z.\-]{0,19}-[0-9]{1,6}\.apk$")


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class ApiKey:
    """The proxai API_SERVER_KEY, re-read when the .env changes (same rules as the voice server)."""

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
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if line.startswith("API_SERVER_KEY="):
                v = line.split("=", 1)[1].strip()
                if v[:1] in "\"'" and v[-1:] == v[:1]:
                    return v[1:-1]
                return re.split(r"\s+#", v, maxsplit=1)[0].strip()
        return ""


KEY = ApiKey(ENV_FILE)


def read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def app_dir(app_id: str) -> Path | None:
    if not ID_RE.match(app_id):
        return None
    d = APPS / app_id
    return d if d.is_dir() and not d.is_symlink() else None


def summary(d: Path) -> dict:
    meta = read_json(d / "proxai-app.json", {})
    status = read_json(d / "status.json", {})
    last = status.get("last_build") or {}
    out = {
        "id": d.name,
        "name": meta.get("name") or last.get("name") or d.name,
        "description": meta.get("description", ""),
        "state": status.get("state", "unknown"),
        "message": status.get("message", ""),
        "updated": status.get("time"),
    }
    if last:
        out["latest"] = {k: last.get(k) for k in ("code", "version", "size", "sha256", "time", "package",
                                                  "features", "permissions", "cert_sha256")}
    return out


def builds(d: Path) -> list[dict]:
    out = []
    for b in read_json(d / "builds.json", []):
        apk = d / str(b.get("apk", ""))
        out.append({"code": b.get("code"), "version": b.get("version"), "time": b.get("time"),
                    "size": b.get("size"), "sha256": b.get("sha256"), "note": b.get("note", ""),
                    "available": apk.is_file()})
    return out


def find_apk(d: Path, code: int | None) -> Path | None:
    entries = read_json(d / "builds.json", [])
    if not entries:
        return None
    entry = entries[-1] if code is None else next((b for b in entries if b.get("code") == code), None)
    if not entry:
        return None
    name = Path(str(entry.get("apk", ""))).name
    if not APK_RE.match(name):
        return None
    apk = d / "dist" / name
    if apk.is_symlink() or not apk.is_file():
        return None
    return apk


class Handler(BaseHTTPRequestHandler):
    server_version = "ProxAIApps/1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, code: int, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self) -> bool:
        key = KEY.get()
        got = self.headers.get("Authorization", "")
        return bool(key) and hmac.compare_digest(got.encode(), f"Bearer {key}".encode())

    def do_GET(self):
        url = urlsplit(self.path)
        path = url.path.rstrip("/")
        # Tailscale Serve strips /apps; accept it too when run behind another proxy.
        if path.startswith("/apps/"):
            path = path[5:]
        if path in ("/health", ""):
            return self._json(200, {"ok": True})
        if not self._authorized():
            log(f"rejected {path} (bad key)")
            return self._json(401, {"error": "invalid API key"})
        parts = [p for p in path.split("/") if p]
        if parts[:2] != ["v1", "apps"]:
            return self._json(404, {"error": "not found"})
        if len(parts) == 2:
            apps = [summary(d) for d in sorted(APPS.iterdir())
                    if d.is_dir() and not d.is_symlink() and ID_RE.match(d.name)] if APPS.is_dir() else []
            return self._json(200, {"apps": apps})
        d = app_dir(parts[2])
        if d is None:
            return self._json(404, {"error": "no such app"})
        if len(parts) == 3:
            return self._json(200, {**summary(d), "builds": builds(d)})
        if len(parts) == 4 and parts[3] == "apk":
            q = parse_qs(url.query)
            code = None
            if "code" in q:
                if not q["code"][0].isdigit():
                    return self._json(400, {"error": "code must be a number"})
                code = int(q["code"][0])
            apk = find_apk(d, code)
            if apk is None:
                return self._json(404, {"error": "no APK for that build"})
            size = apk.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", f'attachment; filename="{apk.name}"')
            self.end_headers()
            with apk.open("rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
            log(f"sent {apk.name} ({size} bytes)")
            return
        return self._json(404, {"error": "not found"})


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
    server, where = make_server()
    log(f"ProxAI apps server on {where}, apps in {APPS}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
