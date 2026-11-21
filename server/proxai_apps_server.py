#!/usr/bin/env python3
"""ProxAI apps server: lets the phone list the apps ProxAI built and download their APKs.

Read-only. It serves what Claude Code + proxai-app-build left in ~/dev/proxai/apps/<id>/
(status.json, builds.json, dist/*.apk). Hermes's `proxai_app` tool starts builds; this only shows
them. Listens on 127.0.0.1:8654; Tailscale Serve publishes it at https://<desktop>.ts.net/apps
(tailnet only). Every request except /health needs the proxai API_SERVER_KEY as a Bearer token.
Runs with the system python3 (standard library only).

  GET /health                          {"ok": true}
  GET /v1/apps                         every app: name, state, progress message, newest vetted build
  GET /v1/apps/<id>                    one app + all its builds (only vetted ones "available")
  GET /v1/apps/<id>/apk                newest vetted APK      (?code=N for an older build, if vetted)
                                       Vetted = the file's own sha256 is in the proxai-apps plugin's record
                                       (~/.local/state/proxai/apps-reach.json: per app "good" [[code, sha]] and
                                       "vetted_sha"); else 404 "waiting for Nathan's yes" / "not vetted". No
                                       readable record: nothing is offered or served (RESERVED-APPS, 2026-11-16)
                                       The owner apps (ids prox-hub, boardroom) are served ONLY from their host-signed
                                       copies, ~/.local/share/proxai-owner-signed/<id>/<dist file name>; else 404
                                       "not signed with the owner key on the PC yet" (OWNER-SIGN, 2026-11-16)
  GET /v1/pc/stats                     the PC's current reading + last hour (per minute) + recent alerts,
                                       and "claude": Claude's plan limits + ProxAI's Claude spend (P2, Hermes
                                       plugin proxai-claude-usage, ~/.local/state/proxai/claude-usage)
  GET /v1/pc/alerts?after=N            alerts with id > N (ProxMobile's background check)
  GET /v1/pc/notices?after=N           PC notices with id > N: scheduled-job results, app ready, wiki
                                       waiting... (Hermes plugin proxai-schedule, ~/.local/state/proxai/notices)
      &wait=S                          (B19, 0.9.9) waits up to S seconds (max 50) for one to arrive, so the
                                       phone's listener gets each notice within a second instead of every 15 min
  GET /v1/approvals?session=ID[&session=ID2]
                                       (F1, 0.9.9) the question a Hermes yes-gate is waiting on for one of these
                                       chats, or null: ProxMobile shows it with Reject / Accept, which send "no" /
                                       "yes" as his next message (proxai-schedule approvals.py,
                                       ~/.local/state/proxai/approvals)
  GET /v1/pc/context?session=ID        the conversation's real size: the latest model call's prompt tokens
                                       (Hermes plugin proxai-context, ~/.local/state/proxai/context)
  GET /v1/scout                        the scout's gig board: every picked gig / app idea / local business
                                       prospect with its status,
                                       notes, hours, calendar block (Hermes plugin proxai-scout, tracker.json),
                                       plus "lanes" (which lanes it scouts, lanes.json) and "lane_names"
  POST /v1/scout/update                {"id", "status"?, "notes"?, "hours"?, "block"?: {"start", "hours"}}:
                                       checked, then queued in scout/inbox/; the scout plugin applies it
                                       (the only writer of the board and of the calendar block).
                                       Or {"lanes": {"gigs"?, "ideas"?, "prospects"?: true|false}} (2026-10-31,
                                       the Gig Board's lane switches), queued the same way
  GET /v1/media?path=/output/<file>    an image or PDF ProxAI made for the chat (its reply says MEDIA:/output/<file>):
                                       png/jpg/gif/webp/pdf from ~/.local/share/proxai/outbox only, 10 MB max
  GET|POST /v1/tutor/<route>           the Study Coach (Hermes plugin proxai-tutor): forwarded as is to the
                                       plugin's Unix socket ~/.local/state/proxai/tutor/api/tutor.sock
                                       (routes in its README); POST bodies up to 640 KB (upload chunks)
  GET /v1/docs/status, POST /v1/docs/unlock|alive|lock|load|ask
                                       proxDocs (Hermes plugin proxai-docs, P4, 2026-11-01): forwarded as is to
                                       the plugin's Unix socket ~/.local/state/proxai/docs/api/docs.sock (routes
                                       in its README). Bodies up to 16 KB, never logged: they carry the proxDocs
                                       password and the questions
  GET /v1/research/<package>/<code>?bytes=N
                                       which APK files of that app version are already on the PC, and
                                       whether N more bytes fit ("ready", else "error") (P3, 2026-10-31)
  PUT /v1/research/<package>/<code>/<base.apk|split_*.apk>?label=..&version=..
                                       one APK file of an app on Nathan's phone (ProxMobile "An app on this
                                       phone"), streamed to ~/dev/proxai/app-research/<package>-<code>/ for
                                       Claude Code + REA (claude_code reverse_engineering, project app-research)
  GET /v1/research/files?bytes=N       whether N more bytes fit, for:
  PUT /v1/research/files/<name>        a firmware image or other file to look inside, any type, into
                                       ~/dev/proxai/app-research/files/<time>-<name> (never overwritten)

Everything is read-only except scout/inbox/, where /v1/scout/update drops one small JSON file per
request, and ~/dev/proxai/app-research/, where /v1/research PUTs APK files (1 GiB each, 20 GB in all,
never past 5 GB free on the disk; nothing there is ever deleted by the server). The /v1/pc routes read the thermal watch's files (proxai_thermal_watch.py: latest.json,
<date>.csv, alerts.jsonl in ~/.local/state/proxai/thermal-watch), also read-only.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import shutil
import socket
import stat
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

APPS = Path(os.getenv("PROXAI_APPS_DIR", "~/dev/proxai/apps")).expanduser()
ENV_FILE = Path(os.getenv("PROXAI_ENV_FILE", "~/.hermes/profiles/proxai/.env")).expanduser()
HOST = os.getenv("PROXAI_APPS_HOST", "127.0.0.1")
PORT = int(os.getenv("PROXAI_APPS_PORT", "8654"))
THERMAL = Path(os.getenv("PROXAI_THERMAL_DIR", "~/.local/state/proxai/thermal-watch")).expanduser()
# P2: proxai-claude-usage writes usage.json here every 10 minutes (Claude Code /usage + Hermes's Claude spend).
CLAUDE_USAGE = Path(os.getenv("PROXAI_CLAUDE_USAGE_DIR", "~/.local/state/proxai/claude-usage")).expanduser()
CONTEXT = Path(os.getenv("PROXAI_CONTEXT_DIR", "~/.local/state/proxai/context")).expanduser()
NOTICES = Path(os.getenv("PROXAI_NOTICES_DIR", "~/.local/state/proxai/notices")).expanduser()
APPROVALS = Path(os.getenv("PROXAI_APPROVALS_DIR", "~/.local/state/proxai/approvals")).expanduser()
NOTICE_WAIT_MAX = 50
SCOUT = Path(os.getenv("PROXAI_SCOUT_DIR", "~/.local/state/proxai/scout")).expanduser()
TUTOR_SOCK = Path(os.getenv("PROXAI_TUTOR_SOCK", "~/.local/state/proxai/tutor/api/tutor.sock")).expanduser()
DOCS_SOCK = Path(os.getenv("PROXAI_DOCS_SOCK", "~/.local/state/proxai/docs/api/docs.sock")).expanduser()
# The sandbox's /output (config.yaml docker volume): files Hermes makes for Nathan (Phase 2, C6).
OUTBOX = Path(os.getenv("PROXAI_OUTBOX_DIR", "~/.local/share/proxai/outbox")).expanduser()
MEDIA_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
               ".pdf": "application/pdf"}
MEDIA_MAX = 10 * 1024 * 1024
MEDIA_PATH_RE = re.compile(r"^(?:/output/)?([A-Za-z0-9_][A-Za-z0-9_. -]{0,120}(?:/[A-Za-z0-9_][A-Za-z0-9_. -]{0,120}){0,4})$")
TUTOR_MAX_BODY = 640 * 1024
TUTOR_ROUTE_RE = re.compile(r"^/v1/tutor(/[a-z0-9][a-z0-9/-]{0,120})$")
DOCS_MAX_BODY = 16 * 1024
DOCS_ROUTE_RE = re.compile(r"^/v1/docs(/(?:status|unlock|alive|lock|load|ask))$")
GIG_STATUSES = ("new", "interested", "pitched", "won", "done", "passed")      # same as proxai-scout tracker.py
IDEA_STATUSES = ("new", "interested", "building", "built", "passed")
PROSPECT_STATUSES = ("new", "interested", "contacted", "replied", "meeting", "client", "passed")   # local businesses
# (2026-10-24); "interested" (2026-10-29) = liked but not emailed yet, the pipeline drafts the email
SCOUT_ID_RE = re.compile(r"^[0-9a-f]{12}$")
MAX_BODY = 8192
MAX_INBOX = 200
STALE_SECS = 15
SESSION_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,120}$")
ID_RE = re.compile(r"^[a-z][a-z0-9-]{1,39}$")
APK_RE = re.compile(r"^[a-z][a-z0-9-]{1,39}-[0-9][0-9A-Za-z.\-]{0,19}-[0-9]{1,6}\.apk$")
# P3 (2026-10-31): APKs of apps on Nathan's phone, for Claude Code + REA. The folder has to exist (a bind in
# proxai-apps.service); the server never makes it, or uploads would land in the unit's private tmpfs home.
RESEARCH = Path(os.getenv("PROXAI_RESEARCH_DIR", "~/dev/proxai/app-research")).expanduser()
RESEARCH_FILE_MAX = 1024 ** 3
RESEARCH_TOTAL_MAX = 20 * 1024 ** 3
RESEARCH_FREE_MIN = 5 * 1024 ** 3
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,60}(\.[A-Za-z][A-Za-z0-9_]{0,60}){1,9}$")
SPLIT_RE = re.compile(r"^(base|split_[A-Za-z0-9_.]{1,100})\.apk$")
FILE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")   # firmware and other files: app-research/files/
RESEARCH_LOCK = threading.Lock()


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


# ---- vetted builds only (RESERVED-APPS round 2, 2026-11-16) ------------------------------------------
# The profile's proxai-apps plugin reads each APK's real package and records the builds it vetted in
# apps-reach.json: per app, "good" = [[code, sha256], ...] and "vetted_sha". This server lists, offers and serves
# an APK only when the file's own sha256 (hashed here, from the same open file it then sends) is in that record,
# so a forged or swapped APK can't reach the phone in the window before the plugin's next sweep. No record, or an
# unreadable one: nothing is served.
REACH = Path(os.getenv("PROXAI_APPS_REACH", "~/.local/state/proxai/apps-reach.json")).expanduser()
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
HELD_FOR_YES = "waiting for Nathan's yes"     # the app has a vetted record, this build isn't in it
NOT_VETTED = "not vetted"                     # no record for the app (or none at all)
_SHA_CACHE: dict[tuple, str] = {}
_SHA_LOCK = threading.Lock()


def vetted_shas(app_id: str, package: str | None = None) -> set[str] | None:
    """The sha256s the plugin vetted for this app, or None when there's no record for it (or no readable record).
    Looked up by app id, then by package. Accepts the apps at the top level or under "apps"."""
    rec = read_json(REACH, None)
    if not isinstance(rec, dict):
        return None
    inner = rec.get("apps")
    apps = inner if isinstance(inner, dict) and all(isinstance(v, dict) for v in inner.values()) else rec
    a = apps.get(app_id)
    if not isinstance(a, dict) and package:
        a = apps.get(package)
    if not isinstance(a, dict):
        return None
    shas = set()
    for g in a.get("good") or []:
        if isinstance(g, (list, tuple)) and len(g) >= 2 and isinstance(g[1], str):
            shas.add(g[1].strip().lower())
    if isinstance(a.get("vetted_sha"), str):
        shas.add(a["vetted_sha"].strip().lower())
    return {s for s in shas if SHA_RE.match(s)}


def _hash_open(f, st: os.stat_result) -> str:
    """sha256 of an open APK, cached by (device, inode, ctime, mtime, size): any write or swap rehashes it."""
    key = (st.st_dev, st.st_ino, st.st_ctime_ns, st.st_mtime_ns, st.st_size)
    with _SHA_LOCK:
        hit = _SHA_CACHE.get(key)
    if hit:
        return hit
    h = hashlib.sha256()
    f.seek(0)
    while chunk := f.read(1024 * 1024):
        h.update(chunk)
    f.seek(0)
    digest = h.hexdigest()
    with _SHA_LOCK:
        if len(_SHA_CACHE) > 512:
            _SHA_CACHE.clear()
        _SHA_CACHE[key] = digest
    return digest


def _package(d: Path) -> str | None:
    last = (read_json(d / "status.json", {}) or {}).get("last_build") or {}
    return last.get("package") if isinstance(last, dict) else None


def _entry_apk(d: Path, entry: dict) -> Path | None:
    name = Path(str(entry.get("apk", ""))).name
    if not APK_RE.match(name):
        return None
    apk = d / "dist" / name
    if apk.is_symlink() or not apk.is_file():
        return None
    return apk


def open_vetted(d: Path, apk: Path, shas: set[str] | None):
    """(open file, None) when this APK's bytes are vetted, else (None, why). The caller sends from that same file,
    so what's sent is what was hashed."""
    try:
        f = apk.open("rb")
    except OSError:
        return None, "no APK for that build"
    try:
        if shas is not None and _hash_open(f, os.fstat(f.fileno())) in shas:
            return f, None
    except OSError:
        pass
    f.close()
    return None, NOT_VETTED if shas is None else HELD_FOR_YES


# ---- the owner apps: host-signed copies only (OWNER-SIGN, 2026-11-16) ------------------------------------
# Prox Hub and BoardRoom are Nathan's own apps. On the PC, after his typed yes, the host (outside every sandbox)
# re-signs their builds with his owner key into ~/.local/share/proxai-owner-signed/<id>/<same file name as dist>.
# For these two ids this server serves ONLY those files: never dist/ (signed with the key every PC-built app
# shares), never a symlink, nothing at all when the folder or the file isn't there. A dir under apps/ that claims
# one of their packages under another id serves nothing. sha256 and size sent to the phone are the served file's.
OWNER_SIGNED = Path(os.getenv("PROXAI_OWNER_SIGNED_DIR", "~/.local/share/proxai-owner-signed")).expanduser()
OWNER_APPS = {"prox-hub": "com.proxai.apps.prox_hub", "boardroom": "com.proxai.apps.boardroom"}
NOT_OWNER_SIGNED = "not signed with the owner key on the PC yet"
NO_APK = "no APK for that build"


def owner_app(d: Path) -> bool:
    """Is this one of the owner apps (by id, or by the package its last build claims)?"""
    return d.name in OWNER_APPS or _package(d) in OWNER_APPS.values()


def _open_owner_signed(d: Path, entry: dict):
    """(open file, path) of this build's host-signed APK, or (None, None)."""
    want = OWNER_APPS.get(d.name)
    pkg = _package(d)
    if want is None or (pkg is not None and pkg != want):
        return None, None
    name = Path(str(entry.get("apk", ""))).name
    if not APK_RE.match(name):
        return None, None
    folder = OWNER_SIGNED / d.name
    if OWNER_SIGNED.is_symlink() or folder.is_symlink() or not folder.is_dir():
        return None, None
    p = folder / name
    try:
        fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)    # a link is refused; a FIFO doesn't hang
    except OSError:
        return None, None
    try:
        regular = stat.S_ISREG(os.fstat(fd).st_mode)
    except OSError:
        regular = False
    if not regular:
        os.close(fd)
        return None, None
    return os.fdopen(fd, "rb"), p


def open_build(d: Path, entry: dict, shas: set[str] | None):
    """(open file, path, None) with the bytes this build is served as, or (None, None, why). Owner apps: the
    host-signed copy only. Every other app: dist/, when vetted."""
    if owner_app(d):
        f, p = _open_owner_signed(d, entry)
        return (f, p, None) if f is not None else (None, None, NOT_OWNER_SIGNED)
    apk = _entry_apk(d, entry)
    if apk is None:
        return None, None, NO_APK
    f, why = open_vetted(d, apk, shas)
    return (f, apk, None) if f is not None else (None, None, why)


def _served_facts(f) -> dict:
    """sha256 and size of the file actually served (an owner app's host-signed copy differs from dist/)."""
    st = os.fstat(f.fileno())
    return {"sha256": _hash_open(f, st), "size": st.st_size}


def newest_served(d: Path, shas: set[str] | None) -> tuple[dict | None, dict, str]:
    """(builds.json entry, facts, "") for the newest build that would be served, or (None, {}, why). facts holds
    the served file's sha256 and size for an owner app, else nothing."""
    owner = owner_app(d)
    why = NOT_OWNER_SIGNED if owner else NO_APK
    for entry in reversed(read_json(d / "builds.json", []) or []):
        if not isinstance(entry, dict):
            continue
        if not owner and _entry_apk(d, entry) is None:
            continue
        f, _, no = open_build(d, entry, shas)
        if f is None:
            why = no
            continue
        with f:
            return entry, (_served_facts(f) if owner else {}), ""
    return None, {}, why


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
        # Only a served build is offered: the newest vetted one (an owner app's: the newest host-signed one).
        owner = owner_app(d)
        entry, facts, why = newest_served(d, vetted_shas(d.name, last.get("package")))
        keys = ("code", "version", "size", "sha256", "time", "package", "features", "permissions", "cert_sha256")
        if entry is None:
            out["held"] = why
        else:
            same = entry.get("code") == last.get("code")
            latest = {k: (last if same else entry).get(k) for k in keys}
            latest["package"] = last.get("package")
            if owner:                           # what's served is the host-signed copy, not dist/'s file
                latest.update(facts, cert_sha256=None, owner_signed=True)
            out["latest"] = latest
            if not same:                        # a newer build is waiting
                out["held"] = NOT_OWNER_SIGNED if owner else HELD_FOR_YES
    return out


def builds(d: Path) -> list[dict]:
    out = []
    owner = owner_app(d)
    shas = vetted_shas(d.name, _package(d))
    for b in read_json(d / "builds.json", []):
        if not isinstance(b, dict):
            continue
        listed = owner or _entry_apk(d, b) is not None
        f, _, why = open_build(d, b, shas) if listed else (None, None, None)
        row = {"code": b.get("code"), "version": b.get("version"), "time": b.get("time"),
               "size": b.get("size"), "sha256": b.get("sha256"), "note": b.get("note", ""),
               "available": f is not None}
        if f is not None:
            with f:
                if owner:
                    row.update(_served_facts(f), owner_signed=True)
        elif listed:
            row["held"] = why
        out.append(row)
    return out


def find_apk(d: Path, code: int | None):
    """(open APK file, its path, None) or (None, None, why): build [code], else the newest served build."""
    shas = vetted_shas(d.name, _package(d))
    entries = read_json(d / "builds.json", []) or []
    if code is None:
        entry, _, why = newest_served(d, shas)
    else:
        entry, why = next((b for b in entries if isinstance(b, dict) and b.get("code") == code), None), NO_APK
    if not entry:
        return None, None, why
    return open_build(d, entry, shas)


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


def claude_usage() -> dict | None:
    """Claude's plan limits (session, week, per-model weeks like Fable) and ProxAI's Claude spend, as
    proxai-claude-usage last wrote them, plus age_s; None until it has run (Vitals shows "not set up")."""
    u = read_json(CLAUDE_USAGE / "usage.json", None)
    if not isinstance(u, dict):
        return None
    try:
        u["age_s"] = max(0, round(time.time() - float(u.get("updated") or 0)))
    except (TypeError, ValueError):
        u["age_s"] = None
    return u


def pc_stats() -> dict:
    latest = read_json(THERMAL / "latest.json", None)
    age = None
    try:
        age = round(time.time() - (THERMAL / "latest.json").stat().st_mtime)
    except OSError:
        pass
    return {"latest": latest, "age_s": age, "watching": latest is not None and age is not None and age <= STALE_SECS,
            "history": pc_history(), "alerts": pc_alerts()[-10:], "claude": claude_usage()}


def wait_for_notices(after: int, wait: float, poll: float = 0.5) -> list[dict]:
    """B19 (11-02): the notices with id > after, waiting up to `wait` seconds for one to arrive. The phone used to
    look every 15 minutes (Android's minimum for background work), so a "class in 9 min" heads-up came late."""
    f = NOTICES / "notices.jsonl"
    deadline = time.monotonic() + wait
    seen = object()
    while True:
        try:
            st = f.stat()
            mark = (st.st_mtime_ns, st.st_size)
        except OSError:
            mark = None
        if mark != seen:
            seen = mark
            found = pc_notices(after)
            if found:
                return found
        left = deadline - time.monotonic()
        if left <= 0:
            return []
        time.sleep(min(poll, left))


def pending_approval(sessions: list[str]) -> dict | None:
    """F1 (11-02): the newest open question a Hermes yes-gate is waiting on for one of these chats."""
    best = None
    for s in sessions:
        name = re.sub(r"[^A-Za-z0-9_.-]", "_", s)[:120]
        try:
            rec = json.loads((APPROVALS / f"{name}.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(rec, dict) or float(rec.get("expires", 0) or 0) < time.time():
            continue
        if best is None or float(rec.get("created", 0) or 0) > float(best.get("created", 0) or 0):
            best = rec
    if best is None:
        return None
    return {k: best.get(k) for k in ("id", "session", "plugin", "question", "detail", "created", "expires")}


NOTICE_TIER_MAX = 20
NOTICE_STEP_MAX = 300  # a notice's decision or next step: a line, not a body (NOTICE-QUOTE)


def notice_out(n: dict) -> dict:
    """One notices.jsonl record as the phone gets it. NOTICE-QUOTE (0.9.15): "no_quote" passes exactly as stored
    (ProxMobile fails closed on any value but false), only when the record has it; "decision" and "next" only when
    they're text, capped at NOTICE_STEP_MAX characters."""
    out = {k: n.get(k) for k in ("id", "time", "kind", "title", "text")}
    if "no_quote" in n:
        out["no_quote"] = n["no_quote"]
    if isinstance(n.get("tier"), str):      # VAULT-UNDO-APP+ (#51): "thread" = chat only, no notification
        out["tier"] = n["tier"][:NOTICE_TIER_MAX]
    for k in ("decision", "next"):
        if isinstance(n.get(k), str):
            out[k] = n[k][:NOTICE_STEP_MAX]
    return out


def pc_notices(after: int = 0, limit: int = 50) -> list[dict]:
    out = []
    for line in _tail_lines(NOTICES / "notices.jsonl", 512 * 1024):
        try:
            n = json.loads(line)
            if int(n.get("id", 0)) > after:
                out.append(notice_out(n))
        except (ValueError, TypeError, AttributeError):
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


SCOUT_LANES = ("gigs", "ideas", "prospects")
LANE_NAMES = {"gigs": "gigs", "ideas": "app ideas", "prospects": "companies"}


def scout_lanes() -> dict:
    """Which lanes the scout scouts (proxai-scout lanes.json, pipeline phase 16); every lane is on by default."""
    try:
        on = json.loads((SCOUT / "lanes.json").read_text(encoding="utf-8")).get("on")
    except (OSError, ValueError, AttributeError):
        on = None
    on = on if isinstance(on, dict) else {}
    return {lane: on.get(lane) is not False for lane in SCOUT_LANES}


def scout_board() -> dict:
    try:
        items = json.loads((SCOUT / "tracker.json").read_text(encoding="utf-8")).get("items", {})
    except (OSError, ValueError):
        items = {}
    rows = sorted((v for v in items.values() if isinstance(v, dict)), key=lambda v: str(v.get("found", "")), reverse=True)
    return {"items": rows, "statuses": {"gigs": list(GIG_STATUSES), "ideas": list(IDEA_STATUSES),
                                        "prospects": list(PROSPECT_STATUSES)},
            "lanes": scout_lanes(), "lane_names": dict(LANE_NAMES)}


def scout_request(req) -> tuple[dict | None, str | None]:
    """The same checks as proxai-scout's tracker.check_request (it checks again before applying)."""
    if not isinstance(req, dict):
        return None, "body must be a JSON object"
    if "lanes" in req:            # 2026-10-31: the Gig Board's lane switches (proxai-scout phase 16b)
        lanes = req["lanes"]
        if set(req) != {"lanes"}:
            return None, "a lanes change has only lanes"
        if not isinstance(lanes, dict) or not lanes or set(lanes) - set(SCOUT_LANES) \
                or not all(isinstance(v, bool) for v in lanes.values()):
            return None, "lanes must be {gigs?, ideas?, prospects?: true or false}"
        return {"lanes": dict(lanes)}, None
    extra = set(req) - {"id", "status", "notes", "hours", "block"}
    if extra:
        return None, f"unknown fields: {sorted(extra)}"
    if not isinstance(req.get("id"), str) or not SCOUT_ID_RE.match(req["id"]):
        return None, "id must be 12 hex characters"
    if "status" in req and req["status"] not in set(GIG_STATUSES) | set(IDEA_STATUSES) | set(PROSPECT_STATUSES):
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


def media_file(path: str) -> Path | None:
    """An image or PDF in the outbox for "/output/<file>" (as ProxAI writes it) or "<file>", else None.
    Only those types, no "..", nothing that resolves (symlinks included) outside the outbox."""
    m = MEDIA_PATH_RE.match(path or "")
    if not m or ".." in m.group(1).split("/"):
        return None
    root = OUTBOX.resolve()
    try:
        f = (root / m.group(1)).resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    if root not in f.parents or not f.is_file() or f.suffix.lower() not in MEDIA_TYPES:
        return None
    return f if f.stat().st_size <= MEDIA_MAX else None


def unix_forward(sock: Path, method: str, route: str, body: bytes | None, timeout: float,
                 down: str, failed: str) -> tuple[int, bytes]:
    """One request to a Hermes plugin over its Unix socket. (status, JSON bytes)."""
    import http.client

    class _Unix(http.client.HTTPConnection):
        def connect(self):
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(self.timeout)
            s.connect(str(sock))
            self.sock = s
    conn = _Unix("localhost", timeout=timeout)
    try:
        conn.request(method, route, body=body, headers={"Content-Type": "application/json"} if body is not None else {})
        r = conn.getresponse()
        return r.status, r.read(4 * 1024 * 1024)
    except (FileNotFoundError, ConnectionRefusedError):
        return 503, json.dumps({"ok": False, "error": down}).encode()
    except (OSError, http.client.HTTPException) as exc:
        return 502, json.dumps({"ok": False, "error": f"{failed} ({type(exc).__name__})"}).encode()
    finally:
        conn.close()


def tutor_forward(method: str, route: str, body: bytes | None) -> tuple[int, bytes]:
    """The Study Coach: grading and question writing take a few seconds."""
    return unix_forward(TUTOR_SOCK, method, route, body, 150,
                        "The Study Coach isn't running on the PC (restart ProxAI)", "Study Coach error")


def docs_forward(method: str, route: str, body: bytes | None) -> tuple[int, bytes]:
    """proxDocs: an answer from the local model can take a minute or two."""
    return unix_forward(DOCS_SOCK, method, route, body, 300,
                        "proxDocs isn't running on the PC (restart ProxAI, or enable the proxai-docs plugin)",
                        "proxDocs error")


def _short(text: str, n: int) -> str:
    """A label or version name from the phone: printable, one line, at most n characters."""
    return "".join(c for c in str(text) if c.isprintable())[:n].strip()


def research_info(pkg: str, code: str) -> dict:
    """The app version's folder and the files in it that really are there at the size app.json says."""
    d = RESEARCH / f"{pkg}-{code}"
    meta = read_json(d / "app.json", {}) if d.is_dir() else {}
    listed = meta.get("files") if isinstance(meta, dict) and isinstance(meta.get("files"), dict) else {}
    files = {}
    for name, f in listed.items():
        try:
            ok = SPLIT_RE.match(name) and isinstance(f, dict) and (d / name).stat().st_size == f.get("size")
        except OSError:
            ok = False
        if ok:
            files[name] = {"size": f["size"], "sha256": str(f.get("sha256", ""))}
    return {"folder": f"app-research/{d.name}", "files": files}


def research_bytes() -> int:
    total = 0
    for p in RESEARCH.rglob("*"):
        try:
            if p.is_file():
                total += p.stat().st_size
        except OSError:
            pass
    return total


def research_room(size: int) -> tuple[int, str] | None:
    """Why `size` more bytes can't go into app-research now, as (status, message); None = they can."""
    if not RESEARCH.is_dir():
        return 503, "the PC has no app-research folder yet (~/dev/proxai/app-research)"
    if research_bytes() + size > RESEARCH_TOTAL_MAX:
        return 507, f"app-research on the PC is full ({RESEARCH_TOTAL_MAX // 1024 ** 3} GB): clear old apps there"
    if shutil.disk_usage(RESEARCH).free - size < RESEARCH_FREE_MIN:
        return 507, "the PC's disk is almost full"
    return None


def _receive(rfile, dest: Path, size: int, apk: bool) -> str:
    """Streams `size` bytes into `dest` (a temp file next to it, fsync, rename); returns their sha256.
    A short body or (apk=True) one that isn't a ZIP raises, and leaves nothing behind."""
    part = dest.parent / f".{dest.name}.{os.getpid()}-{threading.get_ident()}.part"
    digest, left = hashlib.sha256(), size
    try:
        with open(part, "wb") as f:
            while left:
                chunk = rfile.read(min(left, 1 << 20))
                if not chunk:
                    raise ConnectionError(f"the upload stopped {size - left} of {size} bytes in")
                if apk and left == size and not chunk.startswith(b"PK\x03\x04"):
                    raise ValueError("that isn't an APK (not a ZIP file)")
                f.write(chunk)
                digest.update(chunk)
                left -= len(chunk)
            f.flush()
            os.fsync(f.fileno())
        os.replace(part, dest)
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    return digest.hexdigest()


def research_file_name(raw: str) -> str | None:
    """A file name from the phone, made safe: letters, digits, . _ - (anything else becomes _), no leading dot."""
    name = re.sub(r"[^A-Za-z0-9._-]", "_", unquote(raw).strip())[:120].lstrip("._-")
    return name if FILE_NAME_RE.match(name) else None


def research_save_file(rfile, raw_name: str, size: int) -> tuple[int, dict]:
    """A firmware image or other file for REA, into RESEARCH/files/<time>-<name>; never overwrites one."""
    name = research_file_name(raw_name)
    if not name:
        return 400, {"error": "that file name can't be used"}
    full = research_room(size)
    if full:
        return full[0], {"error": full[1]}
    d = RESEARCH / "files"
    d.mkdir(exist_ok=True)
    stored = time.strftime("%Y%m%d-%H%M%S-") + name
    if (d / stored).exists():
        stored = time.strftime("%Y%m%d-%H%M%S-") + f"{threading.get_ident() % 10000}-" + name
    try:
        sha = _receive(rfile, d / stored, size, apk=False)
    except (OSError, ConnectionError, ValueError) as exc:
        log(f"research upload files/{name} failed: {exc}")
        return 400, {"error": f"upload failed: {exc}"}
    log(f"research: files/{stored} ({size} bytes)")
    return 201, {"ok": True, "folder": "app-research/files", "file": stored, "size": size, "sha256": sha}


def research_save(rfile, pkg: str, code: str, name: str, size: int, label: str, version: str) -> tuple[int, dict]:
    """Streams one APK file into RESEARCH/<pkg>-<code>/<name> (temp file, fsync, rename) and notes it in app.json."""
    full = research_room(size)
    if full:
        return full[0], {"error": full[1]}
    d = RESEARCH / f"{pkg}-{code}"
    d.mkdir(exist_ok=True)
    try:
        sha = _receive(rfile, d / name, size, apk=True)
    except (OSError, ConnectionError, ValueError) as exc:
        log(f"research upload {pkg}-{code}/{name} failed: {exc}")
        return 400, {"error": f"upload failed: {exc}"}
    now = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    with RESEARCH_LOCK:   # two files of one app may arrive at once
        meta = read_json(d / "app.json", {})
        meta = meta if isinstance(meta, dict) else {}
        files = meta.get("files") if isinstance(meta.get("files"), dict) else {}
        files[name] = {"size": size, "sha256": sha, "time": now}
        meta.update(package=pkg, version_code=int(code), files=files, updated=now, sent_from="ProxMobile")
        if label:
            meta["label"] = label
        if version:
            meta["version_name"] = version
        tmp = d / f".app.json.{threading.get_ident()}.tmp"
        tmp.write_text(json.dumps(meta, indent=1) + "\n", encoding="utf-8")
        os.replace(tmp, d / "app.json")
    log(f"research: {pkg}-{code}/{name} ({size} bytes)")
    return 201, {"ok": True, **research_info(pkg, code), "file": name, "size": size, "sha256": sha}


class Handler(BaseHTTPRequestHandler):
    server_version = "ProxAIApps/1"

    def log_message(self, fmt, *args):
        pass

    def log_request(self, code="-", size="-"):
        # One line per request in the journal (path and status only; the key is a header and never logged).
        # Added 10-24 to see whether the phone fetches /v1/pc/notices.
        if getattr(self, "_quiet", False):
            return      # a long poll that found nothing: about one a minute, all day (B19)
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

    def _docs(self, method: str, path: str, body: bytes | None):
        m = DOCS_ROUTE_RE.match(path)
        if not m or (method == "GET") != (m.group(1) == "/status"):
            return self._json(404, {"error": "not found"})
        code, data = docs_forward(method, m.group(1), body)
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _tutor(self, method: str, path: str, body: bytes | None):
        m = TUTOR_ROUTE_RE.match(path)
        if not m or "//" in path:
            return self._json(404, {"error": "not found"})
        code, data = tutor_forward(method, m.group(1), body)
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
        if parts == ["v1", "research", "files"]:
            want = parse_qs(url.query).get("bytes", ["0"])[0]
            if not want.isdigit():
                return self._json(400, {"error": "bytes must be a number"})
            full = research_room(int(want))
            return self._json(200, {"folder": "app-research/files", "ready": full is None, **({"error": full[1]} if full else {})})
        if len(parts) == 4 and parts[:2] == ["v1", "research"]:
            if not PACKAGE_RE.match(parts[2]) or not parts[3].isdigit() or len(parts[3]) > 19:
                return self._json(400, {"error": "needs /v1/research/<package>/<version code>"})
            # ?bytes=N: the phone asks first whether N more bytes fit, so a refusal comes with its reason.
            want = parse_qs(url.query).get("bytes", ["0"])[0]
            if not want.isdigit():
                return self._json(400, {"error": "bytes must be a number"})
            full = research_room(int(want))
            info = research_info(parts[2], parts[3])
            return self._json(200, {**info, "ready": full is None, **({"error": full[1]} if full else {})})
        if parts == ["v1", "pc", "stats"]:
            return self._json(200, pc_stats())
        if parts == ["v1", "pc", "alerts"]:
            after = parse_qs(url.query).get("after", ["0"])[0]
            if not after.isdigit():
                return self._json(400, {"error": "after must be a number"})
            return self._json(200, {"alerts": pc_alerts(int(after))})
        if parts == ["v1", "pc", "notices"]:
            q = parse_qs(url.query)
            after, wait = q.get("after", ["0"])[0], q.get("wait", ["0"])[0]
            if not after.isdigit():
                return self._json(400, {"error": "after must be a number"})
            if not wait.isdigit():
                return self._json(400, {"error": "wait must be a number of seconds"})
            wait_s = min(int(wait), NOTICE_WAIT_MAX)
            found = wait_for_notices(int(after), wait_s) if wait_s else pc_notices(int(after))
            self._quiet = bool(wait_s) and not found
            return self._json(200, {"notices": found})
        if parts == ["v1", "approvals"]:
            sessions = [s for s in parse_qs(url.query).get("session", []) if SESSION_RE.match(s)][:8]
            if not sessions:
                return self._json(400, {"error": "session must be one or more chat session ids"})
            return self._json(200, {"approval": pending_approval(sessions)})
        if parts == ["v1", "pc", "context"]:
            session = parse_qs(url.query).get("session", [""])[0]
            if not SESSION_RE.match(session):
                return self._json(400, {"error": "session must be a session id"})
            return self._json(200, pc_context(session))
        if parts == ["v1", "scout"]:
            return self._json(200, scout_board())
        if parts == ["v1", "media"]:
            f = media_file(parse_qs(url.query).get("path", [""])[0])
            if f is None:
                return self._json(404, {"error": "no such image"})
            data = f.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", MEDIA_TYPES[f.suffix.lower()])
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "private, max-age=86400")
            self.end_headers()
            self.wfile.write(data)
            log(f"sent image {f.name} ({len(data)} bytes)")
            return
        if parts[:2] == ["v1", "tutor"]:
            return self._tutor("GET", path, None)
        if parts[:2] == ["v1", "docs"]:
            return self._docs("GET", path, None)
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
            f, apk, why = find_apk(d, code)
            if f is None:
                if why in (HELD_FOR_YES, NOT_VETTED, NOT_OWNER_SIGNED):
                    log(f"held {d.name} build {code if code is not None else 'latest'}: {why}")
                return self._json(404, {"error": why})
            with f:
                size = os.fstat(f.fileno()).st_size
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(size))
                self.send_header("Content-Disposition", f'attachment; filename="{apk.name}"')
                self.end_headers()
                f.seek(0)
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
    if path.startswith("/v1/docs/"):
        try:
            size = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            size = -1
        if not 0 <= size <= DOCS_MAX_BODY:
            self.close_connection = True
            return self._json(413, {"error": "too big"})
        raw = self.rfile.read(size) if size else b"{}"
        try:
            if not isinstance(json.loads(raw.decode("utf-8")), dict):
                raise ValueError
        except (ValueError, UnicodeDecodeError):
            return self._json(400, {"error": "body must be a JSON object"})
        return self._docs("POST", path, raw)
    if path.startswith("/v1/tutor/"):
        try:
            size = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            size = -1
        if not 0 <= size <= TUTOR_MAX_BODY:
            return self._json(413, {"error": "too big"})
        raw = self.rfile.read(size) if size else b"{}"
        try:
            if not isinstance(json.loads(raw.decode("utf-8")), dict):
                raise ValueError
        except (ValueError, UnicodeDecodeError):
            return self._json(400, {"error": "body must be a JSON object"})
        return self._tutor("POST", path, raw)
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


def _do_put(self):
    """P3: one APK file of an app on Nathan's phone, for Claude Code + REA."""
    url = urlsplit(self.path)
    path = url.path.rstrip("/")
    if path.startswith("/apps/"):
        path = path[5:]
    if not self._authorized():
        log(f"rejected PUT {path[:80]} (bad key)")
        return self._json(401, {"error": "invalid API key"})
    parts = [p for p in path.split("/") if p]
    try:
        size = int(self.headers.get("Content-Length") or -1)
    except ValueError:
        size = -1
    if len(parts) == 4 and parts[:3] == ["v1", "research", "files"]:
        if not 0 < size <= RESEARCH_FILE_MAX:
            return self._json(413, {"error": f"a file must be 1 byte to {RESEARCH_FILE_MAX // 1024 ** 2} MB"})
        self.connection.settimeout(120)
        code_, body = research_save_file(self.rfile, parts[3], size)
        if code_ != 201:
            self.close_connection = True
        return self._json(code_, body)
    if len(parts) != 5 or parts[:2] != ["v1", "research"]:
        return self._json(404, {"error": "not found"})
    pkg, code, name = parts[2:]
    if not PACKAGE_RE.match(pkg) or not code.isdigit() or len(code) > 19 or not SPLIT_RE.match(name):
        return self._json(400, {"error": "needs /v1/research/<package>/<version code>/<base.apk or split_*.apk>"})
    if not 0 < size <= RESEARCH_FILE_MAX:
        return self._json(413, {"error": f"an APK file must be 1 byte to {RESEARCH_FILE_MAX // 1024 ** 2} MB"})
    q = parse_qs(url.query)
    self.connection.settimeout(120)   # a phone that stops sending doesn't hold a thread for ever
    code_, body = research_save(self.rfile, pkg, code, name, size,
                                _short(q.get("label", [""])[0], 100), _short(q.get("version", [""])[0], 60))
    if code_ != 201:
        self.close_connection = True   # the rest of an unread body must not be taken for a request
    return self._json(code_, body)


Handler.do_PUT = _do_put


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
