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
  GET /v1/pc/stats                     the PC's current reading + last hour (per minute) + recent alerts
  GET /v1/pc/alerts?after=N            alerts with id > N (ProxMobile's background check)
  GET /v1/pc/notices?after=N           PC notices with id > N: scheduled-job results, app ready, wiki
                                       waiting... (Hermes plugin proxai-schedule, ~/.local/state/proxai/notices)
  GET /v1/pc/context?session=ID        the conversation's real size: the latest model call's prompt tokens
                                       (Hermes plugin proxai-context, ~/.local/state/proxai/context)
  GET /v1/scout                        the scout's gig board: every picked gig / app idea with its status,
                                       notes, hours, calendar block (Hermes plugin proxai-scout, tracker.json)
  POST /v1/scout/update                {"id", "status"?, "notes"?, "hours"?, "block"?: {"start", "hours"}}:
                                       checked, then queued in scout/inbox/; the scout plugin applies it
                                       (the only writer of the board and of the calendar block)

Everything is read-only except scout/inbox/, where /v1/scout/update drops one small JSON file per
request. The /v1/pc routes read the thermal watch's files (proxai_thermal_watch.py: latest.json,
<date>.csv, alerts.jsonl in ~/.local/state/proxai/thermal-watch), also read-only.
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
THERMAL = Path(os.getenv("PROXAI_THERMAL_DIR", "~/.local/state/proxai/thermal-watch")).expanduser()
CONTEXT = Path(os.getenv("PROXAI_CONTEXT_DIR", "~/.local/state/proxai/context")).expanduser()
NOTICES = Path(os.getenv("PROXAI_NOTICES_DIR", "~/.local/state/proxai/notices")).expanduser()
SCOUT = Path(os.getenv("PROXAI_SCOUT_DIR", "~/.local/state/proxai/scout")).expanduser()
GIG_STATUSES = ("new", "interested", "pitched", "won", "done", "passed")      # same as proxai-scout tracker.py
IDEA_STATUSES = ("new", "interested", "building", "built", "passed")
SCOUT_ID_RE = re.compile(r"^[0-9a-f]{12}$")
MAX_BODY = 8192
MAX_INBOX = 200
STALE_SECS = 15
SESSION_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,120}$")
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


# ---- PC stats (thermal watch) --------------------------------------------------------------------

def _tail_lines(path: Path, max_bytes: int) -> list[str]:
    try:
        with path.open("rb") as f:
            end = f.seek(0, 2)
            f.seek(max(0, end - max_bytes))
            data = f.read().decode("utf-8", "replace")
    except OSError:
        return []
    lines = data.splitlines()
    return lines[1:] if end > max_bytes else lines   # first line may be cut


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def pc_history(minutes: int = 60) -> list[dict]:
    """Per-minute peaks for the last hour from today's CSV (lowest free RAM)."""
    from datetime import datetime
    path = THERMAL / f"{datetime.now():%Y-%m-%d}.csv"
    rows = _tail_lines(path, minutes * 60 * 120)
    by_min: dict[str, dict] = {}
    for line in rows:
        f = line.split(",")
        if len(f) < 14 or not re.match(r"^\d\d:\d\d:\d\d$", f[0]):
            continue
        m = by_min.setdefault(f[0][:5], {"minute": f[0][:5]})
        for key, idx in (("gpu_c", 1), ("gpu_w", 2), ("gpu_use", 3), ("cpu_use", 10)):
            v = _num(f[idx])
            if v is not None:
                m[key] = max(m.get(key, v), v)
        cpu = [x for x in (_num(f[8]), _num(f[9])) if x is not None]
        if cpu:
            m["cpu_c"] = max(m.get("cpu_c", max(cpu)), max(cpu))
        ram = _num(f[12])
        if ram is not None:
            m["ram_free_mb"] = min(m.get("ram_free_mb", ram), ram)
    return list(by_min.values())[-minutes:]


def pc_alerts(after: int = 0, limit: int = 50) -> list[dict]:
    out = []
    for line in _tail_lines(THERMAL / "alerts.jsonl", 256 * 1024):
        try:
            a = json.loads(line)
        except ValueError:
            continue
        if int(a.get("id", 0)) > after:
            out.append(a)
    return out[-limit:]


def pc_stats() -> dict:
    latest = read_json(THERMAL / "latest.json", None)
    age = None
    try:
        age = round(time.time() - (THERMAL / "latest.json").stat().st_mtime)
    except OSError:
        pass
    return {"latest": latest, "age_s": age, "watching": latest is not None and age is not None and age <= STALE_SECS,
            "history": pc_history(), "alerts": pc_alerts()[-10:]}


def pc_notices(after: int = 0, limit: int = 50) -> list[dict]:
    out = []
    for line in _tail_lines(NOTICES / "notices.jsonl", 512 * 1024):
        try:
            n = json.loads(line)
            if int(n.get("id", 0)) > after:
                out.append({k: n.get(k) for k in ("id", "time", "kind", "title", "text")})
        except (ValueError, TypeError):
            continue
    return out[-limit:]


def pc_context(session: str) -> dict:
    """The session's latest model call. Hermes renames a session when it compacts, so an unknown id
    falls back to the newest phone call (marked match=latest)."""
    d = read_json(CONTEXT / "sessions.json", {}) or {}

    def aged(rec: dict, match: str) -> dict:
        try:
            age = round(time.time() - time.mktime(time.strptime(rec["time"], "%Y-%m-%dT%H:%M:%S")))
        except (KeyError, TypeError, ValueError):
            age = None
        return {**rec, "match": match, "age_s": age}

    rec = (d.get("sessions") or {}).get(session)
    if isinstance(rec, dict):
        return aged(rec, "session")
    latest = d.get("latest")
    return aged(latest, "latest") if isinstance(latest, dict) else {"match": None}


def scout_board() -> dict:
    try:
        items = json.loads((SCOUT / "tracker.json").read_text(encoding="utf-8")).get("items", {})
    except (OSError, ValueError):
        items = {}
    rows = sorted((v for v in items.values() if isinstance(v, dict)), key=lambda v: str(v.get("found", "")), reverse=True)
    return {"items": rows, "statuses": {"gigs": list(GIG_STATUSES), "ideas": list(IDEA_STATUSES)}}


def scout_request(req) -> tuple[dict | None, str | None]:
    """The same checks as proxai-scout's tracker.check_request (it checks again before applying)."""
    if not isinstance(req, dict):
        return None, "body must be a JSON object"
    extra = set(req) - {"id", "status", "notes", "hours", "block"}
    if extra:
        return None, f"unknown fields: {sorted(extra)}"
    if not isinstance(req.get("id"), str) or not SCOUT_ID_RE.match(req["id"]):
        return None, "id must be 12 hex characters"
    if "status" in req and req["status"] not in set(GIG_STATUSES) | set(IDEA_STATUSES):
        return None, "unknown status"
    if "notes" in req and (not isinstance(req["notes"], str) or len(req["notes"]) > 2000):
        return None, "notes must be text, at most 2000 characters"
    h = req.get("hours")
    if "hours" in req and h is not None and (isinstance(h, bool) or not isinstance(h, (int, float)) or not 0 <= h <= 500):
        return None, "hours must be a number from 0 to 500, or null"
    if "block" in req:
        b = req["block"]
        if not isinstance(b, dict) or set(b) - {"start", "hours"}:
            return None, "block must be {start, hours}"
        if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$", str(b.get("start", ""))):
            return None, "block.start must look like 2026-10-25T14:00 (local time)"
        bh = b.get("hours")
        if isinstance(bh, bool) or not isinstance(bh, (int, float)) or not 0.25 <= bh <= 12:
            return None, "block.hours must be from 0.25 to 12"
    if len(req) == 1:
        return None, "nothing to change"
    return req, None


def queue_scout(req: dict) -> str | None:
    """One file per request in scout/inbox/; None when queued, else why not."""
    box = SCOUT / "inbox"
    try:
        if sum(1 for _ in box.iterdir()) >= MAX_INBOX:
            return "too many changes waiting; try again in a minute"
        name = f"{time.time_ns()}-{os.urandom(3).hex()}"
        tmp = box / f".{name}.tmp"
        tmp.write_text(json.dumps(req), encoding="utf-8")
        os.replace(tmp, box / f"{name}.json")
        return None
    except OSError as exc:
        return f"can't queue the change ({exc.strerror or exc})"


class Handler(BaseHTTPRequestHandler):
    server_version = "ProxAIApps/1"

    def log_message(self, fmt, *args):
        pass

    def log_request(self, code="-", size="-"):
        # One line per request in the journal (path and status only; the key is a header and never logged).
        # Added 10-24 to see whether the phone fetches /v1/pc/notices.
        path = self.path.split("?")[0][:80]
        print(f"{self.command} {path} {code}", file=sys.stderr, flush=True)

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
        if parts == ["v1", "pc", "stats"]:
            return self._json(200, pc_stats())
        if parts == ["v1", "pc", "alerts"]:
            after = parse_qs(url.query).get("after", ["0"])[0]
            if not after.isdigit():
                return self._json(400, {"error": "after must be a number"})
            return self._json(200, {"alerts": pc_alerts(int(after))})
        if parts == ["v1", "pc", "notices"]:
            after = parse_qs(url.query).get("after", ["0"])[0]
            if not after.isdigit():
                return self._json(400, {"error": "after must be a number"})
            return self._json(200, {"notices": pc_notices(int(after))})
        if parts == ["v1", "pc", "context"]:
            session = parse_qs(url.query).get("session", [""])[0]
            if not SESSION_RE.match(session):
                return self._json(400, {"error": "session must be a session id"})
            return self._json(200, pc_context(session))
        if parts == ["v1", "scout"]:
            return self._json(200, scout_board())
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


def _do_post(self):
    path = urlsplit(self.path).path.rstrip("/")
    if path.startswith("/apps/"):
        path = path[5:]
    if not self._authorized():
        log(f"rejected POST {path} (bad key)")
        return self._json(401, {"error": "invalid API key"})
    if path != "/v1/scout/update":
        return self._json(404, {"error": "not found"})
    try:
        size = int(self.headers.get("Content-Length") or 0)
    except ValueError:
        size = -1
    if not 0 < size <= MAX_BODY:
        return self._json(400, {"error": f"body must be 1 to {MAX_BODY} bytes of JSON"})
    try:
        body = json.loads(self.rfile.read(size).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return self._json(400, {"error": "body must be JSON"})
    req, err = scout_request(body)
    if err:
        return self._json(400, {"error": err})
    err = queue_scout(req)
    if err:
        return self._json(503, {"error": err})
    return self._json(202, {"ok": True, "queued": True, "note": "applied on the PC within a minute"})


Handler.do_POST = _do_post


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
