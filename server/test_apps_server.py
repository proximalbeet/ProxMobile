"""Tests for proxai_apps_server.py: a real server on a free port, a temp apps folder, a test key.

Run: python3 server/test_apps_server.py
     APPS_FROM=<folder with built apps> python3 server/test_apps_server.py   (also serves real builds)
"""
from __future__ import annotations

import importlib.util
import json
import os
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
TMP = Path(tempfile.mkdtemp(prefix="apps-server-test-", dir=os.environ.get("TMPDIR")))
APPS = TMP / "apps"
ENV = TMP / ".env"
KEY = "test-key-" + "x" * 30
THERMAL = TMP / "thermal"
CONTEXT = TMP / "context"
NOTICES = TMP / "notices"
SCOUT = TMP / "scout"
os.environ["PROXAI_SCOUT_DIR"] = str(SCOUT)
TUTOR_SOCK = TMP / "tutor.sock"
OUTBOX = TMP / "outbox"
os.environ["PROXAI_OUTBOX_DIR"] = str(OUTBOX)
os.environ["PROXAI_TUTOR_SOCK"] = str(TUTOR_SOCK)
DOCS_SOCK = TMP / "docs.sock"
os.environ["PROXAI_DOCS_SOCK"] = str(DOCS_SOCK)
CLAUDE_USAGE = TMP / "claude-usage"
os.environ["PROXAI_CLAUDE_USAGE_DIR"] = str(CLAUDE_USAGE)
RESEARCH = TMP / "app-research"
os.environ["PROXAI_RESEARCH_DIR"] = str(RESEARCH)
APPROVALS = TMP / "approvals"
os.environ["PROXAI_APPROVALS_DIR"] = str(APPROVALS)
REACH = TMP / "apps-reach.json"          # the proxai-apps plugin's vetted-builds record (RESERVED-APPS)
os.environ["PROXAI_APPS_REACH"] = str(REACH)
OWNER_SIGNED = TMP / "proxai-owner-signed"   # the host's owner-signed copies of Prox Hub and BoardRoom (OWNER-SIGN)
os.environ["PROXAI_OWNER_SIGNED_DIR"] = str(OWNER_SIGNED)
os.environ.update(PROXAI_APPS_DIR=str(APPS), PROXAI_ENV_FILE=str(ENV), PROXAI_APPS_PORT="0",
                  PROXAI_THERMAL_DIR=str(THERMAL), PROXAI_CONTEXT_DIR=str(CONTEXT), PROXAI_NOTICES_DIR=str(NOTICES))

spec = importlib.util.spec_from_file_location("apps_server", HERE / "proxai_apps_server.py")
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)


def make_app(app_id="tip-calc", codes=(1, 2)):
    d = APPS / app_id
    (d / "dist").mkdir(parents=True, exist_ok=True)
    (d / "proxai-app.json").write_text(json.dumps({"id": app_id, "name": "Tip Calc", "description": "Splits bills"}))
    entries = []
    for c in codes:
        name = f"{app_id}-1.{c}-{c}.apk"
        (d / "dist" / name).write_bytes(b"APK" + bytes([c]) * 10)
        entries.append({"code": c, "version": f"1.{c}", "apk": f"dist/{name}", "size": 13, "sha256": "s", "time": "t"})
    (d / "builds.json").write_text(json.dumps(entries))
    (d / "status.json").write_text(json.dumps({"state": "built", "message": "ready", "time": "t",
                                               "last_build": {**entries[-1], "package": f"com.proxai.apps.{app_id.replace('-', '_')}",
                                                              "features": [], "permissions": []}}))
    return d


RECORD: dict = {}


def sha_of(p: Path) -> str:
    import hashlib
    return hashlib.sha256(p.read_bytes()).hexdigest()


def vet(app_id: str, codes=None, how: str = "good"):
    """Record this app's builds (all in builds.json, or just [codes]) as vetted, the way the plugin does."""
    d = APPS / app_id
    good = []
    for b in json.loads((d / "builds.json").read_text()):
        f = d / "dist" / Path(b["apk"]).name
        if (codes is None or b["code"] in codes) and f.is_file():
            good.append([b["code"], sha_of(f)])
    RECORD[app_id] = {"good": good} if how == "good" else {"good": [], "vetted_sha": good[-1][1]}
    REACH.write_text(json.dumps(RECORD))


class AppsServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        APPS.mkdir(parents=True)
        ENV.write_text(f"API_SERVER_KEY={KEY}  # comment\n")
        cls.server = srv.ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        make_app()
        vet("tip-calc")
        (APPS / ".signing").mkdir()
        (APPS / ".signing" / "password").write_text("secret")
        if os.environ.get("APPS_FROM"):
            for d in Path(os.environ["APPS_FROM"]).iterdir():
                if d.is_dir() and not d.name.startswith("."):
                    shutil.copytree(d, APPS / d.name)
                    vet(d.name)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        shutil.rmtree(TMP, ignore_errors=True)

    def get(self, path, key=KEY):
        req = urllib.request.Request(self.base + path, headers={"Authorization": f"Bearer {key}"} if key else {})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def post(self, path, body, key=KEY, raw=None):
        data = raw if raw is not None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method="POST",
                                     headers={"Content-Type": "application/json",
                                              **({"Authorization": f"Bearer {key}"} if key else {})})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def put(self, path, data, key=KEY):
        req = urllib.request.Request(self.base + path, data=data, method="PUT",
                                     headers={"Content-Type": "application/vnd.android.package-archive",
                                              **({"Authorization": f"Bearer {key}"} if key else {})})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read() or b"{}")

    def test_research_upload_and_listing(self):
        """P3 (2026-10-31): the phone sends an installed app's APK files; GET says what's there already."""
        RESEARCH.mkdir(exist_ok=True)
        pkg = "com.example.notes"
        code, info = self.get_json(f"/v1/research/{pkg}/42?bytes=2048")
        self.assertEqual((code, info["ready"], info["files"]), (200, True, {}))
        base = b"PK\x03\x04" + b"b" * 3000
        code, r = self.put(f"/v1/research/{pkg}/42/base.apk?label=Notes%20Plus&version=4.2.0", base)
        self.assertEqual((code, r["file"], r["size"]), (201, "base.apk", len(base)), r)
        self.assertEqual(r["folder"], "app-research/com.example.notes-42")
        split = b"PK\x03\x04" + b"s" * 100
        code, r = self.put(f"/apps/v1/research/{pkg}/42/split_config.arm64_v8a.apk", split)   # Tailscale's /apps prefix
        self.assertEqual(code, 201, r)
        d = RESEARCH / f"{pkg}-42"
        self.assertEqual((d / "base.apk").read_bytes(), base)
        meta = json.loads((d / "app.json").read_text())
        self.assertEqual((meta["label"], meta["version_name"], meta["version_code"]), ("Notes Plus", "4.2.0", 42))
        import hashlib
        self.assertEqual(meta["files"]["base.apk"]["sha256"], hashlib.sha256(base).hexdigest())
        code, info = self.get_json(f"/v1/research/{pkg}/42")
        self.assertEqual(sorted(info["files"]), ["base.apk", "split_config.arm64_v8a.apk"])
        (d / "split_config.arm64_v8a.apk").write_bytes(b"PK\x03\x04")          # changed on disk: not "already there"
        self.assertEqual(sorted(self.get_json(f"/v1/research/{pkg}/42")[1]["files"]), ["base.apk"])
        self.assertFalse(list(d.glob(".*part")))

    def test_research_refuses_bad_names_and_non_apks(self):
        RESEARCH.mkdir(exist_ok=True)
        apk = b"PK\x03\x04" + b"x" * 10
        for path in ("/v1/research/com.example.a/1/../base.apk", "/v1/research/com.example.a/1/notes.txt",
                     "/v1/research/com.example.a/1/foo.apk", "/v1/research/noperiod/1/base.apk",
                     "/v1/research/com.example.a/1x/base.apk", "/v1/research/com.example.a/1/split_.apk",
                     "/v1/research/com.example.a/1/split_a%2Fb.apk", "/v1/research/com.example.a/1/sub/base.apk"):
            self.assertIn(self.put(path, apk)[0], (400, 404), path)
        code, r = self.put("/v1/research/com.example.a/1/base.apk", b"MZ not a zip at all")
        self.assertEqual(code, 400)
        self.assertIn("isn't an APK", r["error"])
        self.assertFalse((RESEARCH / "com.example.a-1" / "base.apk").exists())
        self.assertFalse(list(RESEARCH.rglob("*.part")))
        self.assertEqual(self.put("/v1/research/com.example.a/1/base.apk", apk, key="wrong")[0], 401)
        self.assertEqual(self.get("/v1/research/com.example.a/1", key=None)[0], 401)

    def test_research_needs_the_folder_and_room(self):
        RESEARCH.mkdir(exist_ok=True)
        away = TMP / "app-research-away"
        RESEARCH.rename(away)
        try:
            code, info = self.get_json("/v1/research/com.example.b/7?bytes=10")
            self.assertEqual((code, info["ready"]), (200, False))
            self.assertIn("no app-research folder", info["error"])
            self.assertEqual(self.put("/v1/research/com.example.b/7/base.apk", b"PK\x03\x04")[0], 503)
            self.assertFalse(RESEARCH.exists())                                   # never made by the server
        finally:
            away.rename(RESEARCH)
        old_file, old_total = srv.RESEARCH_FILE_MAX, srv.RESEARCH_TOTAL_MAX
        try:
            srv.RESEARCH_FILE_MAX = 100
            self.assertEqual(self.put("/v1/research/com.example.b/7/base.apk", b"PK\x03\x04" + b"z" * 200)[0], 413)
            srv.RESEARCH_FILE_MAX, srv.RESEARCH_TOTAL_MAX = old_file, 50
            info = self.get_json("/v1/research/com.example.b/7?bytes=60")[1]
            self.assertFalse(info["ready"])
            self.assertIn("full", info["error"])
            self.assertEqual(self.put("/v1/research/com.example.b/7/base.apk", b"PK\x03\x04" + b"z" * 60)[0], 507)
        finally:
            srv.RESEARCH_FILE_MAX, srv.RESEARCH_TOTAL_MAX = old_file, old_total

    def test_research_files_for_firmware(self):
        """Nathan 10-31: REA is for other apps, widgets and firmware. Any file type, a safe name, never overwritten."""
        RESEARCH.mkdir(exist_ok=True)
        code, info = self.get_json("/v1/research/files?bytes=4096")
        self.assertEqual((code, info["ready"], info["folder"]), (200, True, "app-research/files"))
        fw = b"\x27\x05\x19\x56" + b"u-boot image" + b"\x00" * 500        # not a ZIP: fine here
        code, r = self.put("/v1/research/files/router%20fw%20v2.bin", fw)
        self.assertEqual(code, 201, r)
        self.assertRegex(r["file"], r"^\d{8}-\d{6}-router_fw_v2\.bin$")
        self.assertEqual((RESEARCH / "files" / r["file"]).read_bytes(), fw)
        code, again = self.put("/v1/research/files/router%20fw%20v2.bin", b"second")   # same name, same second
        self.assertEqual(code, 201, again)
        self.assertNotEqual(again["file"], r["file"])
        self.assertEqual((RESEARCH / "files" / r["file"]).read_bytes(), fw)            # the first one stays
        code, r = self.put("/v1/research/files/..%2F..%2Fetc%2Fpasswd", b"x")
        self.assertEqual(code, 201, r)
        self.assertTrue(r["file"].endswith("-etc_passwd"), r)
        self.assertTrue((RESEARCH / "files" / r["file"]).is_file())                     # inside files/, nowhere else
        self.assertEqual(self.put("/v1/research/files/...", b"x")[0], 400)
        self.assertIn(self.put("/v1/research/files/a/b.bin", b"x")[0], (400, 404))   # no sub-folders
        self.assertEqual(self.put("/v1/research/files/fw.bin", b"x", key="wrong")[0], 401)
        old = srv.RESEARCH_FILE_MAX
        try:
            srv.RESEARCH_FILE_MAX = 10
            self.assertEqual(self.put("/v1/research/files/big.bin", b"y" * 11)[0], 413)
        finally:
            srv.RESEARCH_FILE_MAX = old
        self.assertFalse(list((RESEARCH / "files").glob(".*")))

    def test_research_upload_cut_off_leaves_nothing(self):
        import socket
        RESEARCH.mkdir(exist_ok=True)
        with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=10) as s:
            s.sendall((f"PUT /v1/research/com.example.c/3/base.apk HTTP/1.1\r\nHost: x\r\n"
                       f"Authorization: Bearer {KEY}\r\nContent-Length: 5000\r\n\r\n").encode() + b"PK\x03\x04" + b"q" * 100)
            s.shutdown(socket.SHUT_WR)
            chunks = []
            while chunk := s.recv(4096):   # headers and body come as two writes
                chunks.append(chunk)
        reply = b"".join(chunks).decode(errors="replace")
        self.assertIn(" 400 ", reply.splitlines()[0])
        self.assertIn("stopped", reply)
        d = RESEARCH / "com.example.c-3"
        self.assertFalse((d / "base.apk").exists())
        self.assertFalse(list(d.glob(".*")))

    def get_json(self, path, key=KEY):
        code, body, _ = self.get(path, key)
        return code, json.loads(body or b"{}")

    def test_scout_board_and_updates(self):
        (SCOUT / "inbox").mkdir(parents=True, exist_ok=True)
        (SCOUT / "tracker.json").write_text(json.dumps({"items": {
            "aaaaaaaaaaaa": {"id": "aaaaaaaaaaaa", "lane": "gigs", "title": "Old gig", "status": "won", "found": "2026-10-20T05:00:00"},
            "bbbbbbbbbbbb": {"id": "bbbbbbbbbbbb", "lane": "ideas", "name": "Quote Builder", "status": "new", "found": "2026-10-24T05:00:00"}}}))
        code, body, _ = self.get("/v1/scout")
        board = json.loads(body)
        self.assertEqual(code, 200)
        self.assertEqual([i["id"] for i in board["items"]], ["bbbbbbbbbbbb", "aaaaaaaaaaaa"])   # newest first
        self.assertIn("pitched", board["statuses"]["gigs"])
        self.assertIn("contacted", board["statuses"]["prospects"])
        self.assertIn("interested", board["statuses"]["prospects"])           # liked, not emailed yet (2026-10-29)
        self.assertEqual(self.post("/v1/scout/update", {"id": "bbbbbbbbbbbb", "status": "meeting"})[0], 202)
        for f in (SCOUT / "inbox").glob("*.json"):
            f.unlink()
        self.assertEqual(self.get("/v1/scout", key="wrong")[0], 401)

        code, r = self.post("/v1/scout/update", {"id": "aaaaaaaaaaaa", "status": "done", "hours": 5,
                                                  "block": {"start": "2026-10-26T14:00", "hours": 2}})
        self.assertEqual((code, r["queued"]), (202, True))
        queued = list((SCOUT / "inbox").glob("*.json"))
        self.assertEqual(len(queued), 1)
        self.assertEqual(json.loads(queued[0].read_text())["status"], "done")
        self.assertEqual(json.loads((SCOUT / "tracker.json").read_text())["items"]["aaaaaaaaaaaa"]["status"], "won")

        for bad in ({"id": "../x", "status": "won"}, {"id": "aaaaaaaaaaaa", "status": "hacked"},
                    {"id": "aaaaaaaaaaaa", "notes": "x" * 2001}, {"id": "aaaaaaaaaaaa", "hours": True},
                    {"id": "aaaaaaaaaaaa", "block": {"start": "tomorrow", "hours": 2}},
                    {"id": "aaaaaaaaaaaa", "block": {"start": "2026-10-26T14:00", "hours": 13}},
                    {"id": "aaaaaaaaaaaa", "status": "won", "x": 1}, {"id": "aaaaaaaaaaaa"}, ["list"]):
            self.assertEqual(self.post("/v1/scout/update", bad)[0], 400, bad)
        self.assertEqual(self.post("/v1/scout/update", None, raw=b"x" * 9000)[0], 400)
        self.assertEqual(self.post("/v1/scout/update", None, raw=b"{nope")[0], 400)
        self.assertEqual(self.post("/v1/scout/update", {"id": "aaaaaaaaaaaa", "status": "done"}, key="wrong")[0], 401)
        self.assertEqual(self.post("/v1/apps", {"id": "aaaaaaaaaaaa", "status": "done"})[0], 404)
        self.assertEqual(len(list((SCOUT / "inbox").glob("*.json"))), 1)       # nothing else got in

    def test_scout_lanes(self):
        """2026-10-31: the board says which lanes the scout scouts; the Gig Board's switches queue a lanes change."""
        (SCOUT / "inbox").mkdir(parents=True, exist_ok=True)
        for f in (SCOUT / "inbox").glob("*.json"):
            f.unlink()
        (SCOUT / "lanes.json").unlink(missing_ok=True)
        board = json.loads(self.get("/v1/scout")[1])
        self.assertEqual(board["lanes"], {"gigs": True, "ideas": True, "prospects": True})       # all on by default
        self.assertEqual(board["lane_names"]["prospects"], "companies")
        (SCOUT / "lanes.json").write_text(json.dumps({"on": {"gigs": True, "ideas": False, "prospects": True}}))
        self.assertFalse(json.loads(self.get("/v1/scout")[1])["lanes"]["ideas"])
        code, r = self.post("/v1/scout/update", {"lanes": {"ideas": True, "prospects": False}})
        self.assertEqual((code, r["queued"]), (202, True))
        (q,) = list((SCOUT / "inbox").glob("*.json"))
        self.assertEqual(json.loads(q.read_text()), {"lanes": {"ideas": True, "prospects": False}})
        for bad in ({"lanes": {}}, {"lanes": {"ideas": "no"}}, {"lanes": {"jobs": True}}, {"lanes": [True]},
                    {"lanes": {"ideas": True}, "id": "aaaaaaaaaaaa"}, {"lanes": {"ideas": 1}}):
            self.assertEqual(self.post("/v1/scout/update", bad)[0], 400, bad)
        self.assertEqual(len(list((SCOUT / "inbox").glob("*.json"))), 1)                       # nothing else got in
        (SCOUT / "lanes.json").unlink()
        q.unlink()

    def test_tutor_is_forwarded_to_the_plugin_socket(self):
        import socketserver
        from http.server import BaseHTTPRequestHandler
        seen = []

        class Fake(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, body):
                data = json.dumps({"ok": True, "path": self.path, "body": body}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                seen.append(("GET", self.path))
                self._reply(None)

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n))
                seen.append(("POST", self.path))
                self._reply(body)

        class U(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
            daemon_threads = True

            def get_request(self):
                r, _ = super().get_request()
                return r, ("unix", 0)

        code, raw, _ = self.get("/v1/tutor/notebooks")
        self.assertEqual(code, 503)                                    # plugin not running yet
        self.assertIn("isn't running", json.loads(raw)["error"])
        fake = U(str(TUTOR_SOCK), Fake)
        threading.Thread(target=fake.serve_forever, daemon=True).start()
        try:
            code, raw, _ = self.get("/apps/v1/tutor/notebooks")
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(raw)["path"], "/notebooks")
            code, out = self.post("/v1/tutor/notebooks/bio-1a2b/answer", {"session": "s", "text": "x" * 300_000})
            self.assertEqual(code, 200)
            self.assertEqual(out["path"], "/notebooks/bio-1a2b/answer")
            self.assertEqual(len(out["body"]["text"]), 300_000)       # big upload chunks get through
            self.assertEqual(self.post("/v1/tutor/notebooks", {"x": "y" * 700_000})[0], 413)
            self.assertEqual(self.post("/v1/tutor/notebooks", None, raw=b"[1]")[0], 400)
            self.assertEqual(self.get("/v1/tutor/notebooks", key="wrong")[0], 401)
            for bad in ("/v1/tutor/../apps", "/v1/tutor//x", "/v1/tutor/NoteBooks", "/v1/tutor"):
                self.assertEqual(self.get(bad)[0], 404, bad)
            self.assertEqual(len(seen), 2)
        finally:
            fake.shutdown()
            fake.server_close()

    def test_docs_is_forwarded_to_the_plugin_socket(self):
        """proxDocs (P4): six routes to the plugin's socket; the password in the body is never logged."""
        import socketserver
        from http.server import BaseHTTPRequestHandler
        seen = []

        class Fake(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, body):
                data = json.dumps({"ok": True, "path": self.path, "body": body}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                seen.append(("GET", self.path))
                self._reply(None)

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n))
                seen.append(("POST", self.path))
                self._reply(body)

        class U(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
            daemon_threads = True

            def get_request(self):
                r, _ = super().get_request()
                return r, ("unix", 0)

        logged = []
        real_log = srv.log
        srv.log = lambda msg: logged.append(msg)
        try:
            code, raw, _ = self.get("/v1/docs/status")
            self.assertEqual(code, 503)                                # plugin not running (or not enabled)
            self.assertIn("proxai-docs", json.loads(raw)["error"])
            fake = U(str(DOCS_SOCK), Fake)
            threading.Thread(target=fake.serve_forever, daemon=True).start()
            try:
                code, raw, _ = self.get("/apps/v1/docs/status")
                self.assertEqual((code, json.loads(raw)["path"]), (200, "/status"))
                secret = "my proxdocs password"
                code, out = self.post("/v1/docs/unlock", {"password": secret, "where": "phone", "relock_seconds": 60})
                self.assertEqual((code, out["path"], out["body"]["password"]), (200, "/unlock", secret))
                for route in ("alive", "lock", "load", "ask"):
                    self.assertEqual(self.post(f"/v1/docs/{route}", {"token": "t"})[1]["path"], f"/{route}")
                self.assertEqual(self.post("/v1/docs/ask", {"question": "x" * 17_000})[0], 413)
                self.assertEqual(self.post("/v1/docs/ask", None, raw=b"[1]")[0], 400)
                self.assertEqual(self.post("/v1/docs/unlock", {"password": secret}, key="wrong")[0], 401)
                self.assertEqual(self.post("/v1/docs/status", {})[0], 404)          # status is GET only
                for bad in ("/v1/docs/ask", "/v1/docs/../apps", "/v1/docs//status", "/v1/docs/Status", "/v1/docs",
                            "/v1/docs/notebooks"):
                    self.assertEqual(self.get(bad)[0], 404, bad)
                self.assertEqual(len(seen), 6)
            finally:
                fake.shutdown()
                fake.server_close()
        finally:
            srv.log = real_log
        self.assertFalse(any(secret in m for m in logged))

    def test_health_needs_no_key(self):
        self.assertEqual(self.get("/health", key=None)[0], 200)

    def test_key_required(self):
        self.assertEqual(self.get("/v1/apps", key=None)[0], 401)
        self.assertEqual(self.get("/v1/apps", key="wrong")[0], 401)

    def test_list(self):
        code, body, _ = self.get("/v1/apps")
        self.assertEqual(code, 200)
        apps = {a["id"]: a for a in json.loads(body)["apps"]}
        self.assertNotIn(".signing", apps)
        a = apps["tip-calc"]
        self.assertEqual((a["name"], a["state"], a["latest"]["code"]), ("Tip Calc", "built", 2))
        self.assertEqual(a["latest"]["package"], "com.proxai.apps.tip_calc")

    def test_detail_lists_builds(self):
        code, body, _ = self.get("/v1/apps/tip-calc")
        self.assertEqual(code, 200)
        self.assertEqual([b["code"] for b in json.loads(body)["builds"]], [1, 2])

    def test_apk_latest_and_older(self):
        code, body, headers = self.get("/v1/apps/tip-calc/apk")
        self.assertEqual((code, body), (200, b"APK" + bytes([2]) * 10))
        self.assertEqual(headers["Content-Type"], "application/vnd.android.package-archive")
        self.assertEqual(self.get("/v1/apps/tip-calc/apk?code=1")[1], b"APK" + bytes([1]) * 10)
        self.assertEqual(self.get("/v1/apps/tip-calc/apk?code=9")[0], 404)
        self.assertEqual(self.get("/v1/apps/tip-calc/apk?code=x")[0], 400)

    def test_tailscale_prefix_accepted(self):
        self.assertEqual(self.get("/apps/v1/apps")[0], 200)

    def test_no_escape(self):
        for path in ("/v1/apps/..%2F..%2Fetc", "/v1/apps/.signing", "/v1/apps/../.signing/password",
                     "/v1/apps/tip-calc/../../.env", "/v1/apps/TIP"):
            code, body, _ = self.get(path)
            self.assertIn(code, (404,), path)
            self.assertNotIn(b"secret", body)
            self.assertNotIn(KEY.encode(), body)

    def test_media_serves_only_outbox_images(self):
        """Phase 2, C6 (10-29): images ProxAI made for the chat (MEDIA:/output/<file> in its reply)."""
        (OUTBOX / "charts").mkdir(parents=True)
        png = b"\x89PNG\r\n\x1a\n" + b"x" * 50
        (OUTBOX / "chart.png").write_bytes(png)
        (OUTBOX / "charts" / "week 44.jpg").write_bytes(b"\xff\xd8jpg")
        (OUTBOX / "notes.txt").write_text("not an image")
        (OUTBOX / "big.png").write_bytes(b"x" * (srv.MEDIA_MAX + 1))
        (OUTBOX / "link.png").symlink_to(ENV)
        code, body, headers = self.get("/v1/media?path=/output/chart.png")
        self.assertEqual((code, body, headers["Content-Type"]), (200, png, "image/png"))
        self.assertEqual(self.get("/v1/media?path=charts/week%2044.jpg")[0], 200)
        (OUTBOX / "quiz2-cheatsheet.pdf").write_bytes(b"%PDF-1.7")
        (OUTBOX / "guide.html").write_text("<script>")
        code, body, headers = self.get("/v1/media?path=/output/quiz2-cheatsheet.pdf")
        self.assertEqual((code, headers["Content-Type"]), (200, "application/pdf"))
        self.assertEqual(self.get("/apps/v1/media?path=/output/chart.png")[0], 200)      # behind Tailscale Serve
        for bad in ("/output/notes.txt", "/output/guide.html", "/output/big.png", "/output/link.png", "/output/../.env",
                    "/output/charts/../../.env", "../.env", "/etc/passwd", "/output/missing.png", "",
                    "/workspace/proxai/x.png", "/output/.hidden.png"):
            code, body, _ = self.get("/v1/media?path=" + urllib.parse.quote(bad))
            self.assertEqual(code, 404, bad)
            self.assertNotIn(KEY.encode(), body)
        self.assertEqual(self.get("/v1/media?path=/output/chart.png", key="wrong")[0], 401)

    def test_apk_entry_pointing_outside_dist_refused(self):
        d = make_app("evil-app", codes=(1,))
        b = json.loads((d / "builds.json").read_text())
        b[0]["apk"] = "../../.signing/password"
        (d / "builds.json").write_text(json.dumps(b))
        self.assertEqual(self.get("/v1/apps/evil-app/apk")[0], 404)

    # ---- RESERVED-APPS round 2 (2026-11-16): only vetted APKs reach the phone ----

    def _app(self, app_id):
        return {a["id"]: a for a in json.loads(self.get("/v1/apps")[1])["apps"]}[app_id]

    def test_a_vetted_build_is_served(self):
        make_app("vet-ok", codes=(1, 2))
        vet("vet-ok")
        code, body, _ = self.get("/v1/apps/vet-ok/apk")
        self.assertEqual((code, body), (200, b"APK" + bytes([2]) * 10))
        self.assertEqual(self.get("/v1/apps/vet-ok/apk?code=1")[1], b"APK" + bytes([1]) * 10)
        self.assertEqual(self._app("vet-ok")["latest"]["code"], 2)
        self.assertNotIn("held", self._app("vet-ok"))
        make_app("vet-sha", codes=(1,))
        vet("vet-sha", how="vetted_sha")                                            # "vetted_sha" alone counts too
        self.assertEqual(self.get("/v1/apps/vet-sha/apk")[0], 200)

    def test_a_swapped_file_with_the_same_name_is_not_served(self):
        import time as _t
        d = make_app("vet-swap", codes=(1,))
        vet("vet-swap")
        apk = d / "dist" / "vet-swap-1.1-1.apk"
        self.assertEqual(self.get("/v1/apps/vet-swap/apk")[0], 200)                 # hashed and cached
        forged = d / "dist" / "forged.tmp"
        forged.write_bytes(b"EVIL" + b"\x01" * 9)                                   # same size, new file
        os.replace(forged, apk)
        code, body, _ = self.get("/v1/apps/vet-swap/apk")
        self.assertEqual((code, json.loads(body)["error"]), (404, "waiting for Nathan's yes"))
        self.assertEqual(self.get("/v1/apps/vet-swap/apk?code=1")[0], 404)
        self.assertNotIn("latest", self._app("vet-swap"))                          # not offered for install
        self.assertEqual(self._app("vet-swap")["held"], "waiting for Nathan's yes")
        self.assertEqual([b["available"] for b in json.loads(self.get("/v1/apps/vet-swap")[1])["builds"]], [False])
        _t.sleep(0.05)                                                              # past the clock tick of the swap
        apk.write_bytes(b"APK" + bytes([1]) * 10)                                   # the real bytes again: served
        self.assertEqual(self.get("/v1/apps/vet-swap/apk")[0], 200)
        _t.sleep(0.05)
        with apk.open("r+b") as f:                                                  # in place, same size and inode
            f.write(b"EVIL")
        self.assertEqual(self.get("/v1/apps/vet-swap/apk")[0], 404)

    def test_an_unvetted_newer_build_is_not_listed(self):
        make_app("vet-new", codes=(1, 2))
        vet("vet-new", codes=(1,))
        a = self._app("vet-new")
        self.assertEqual(a["latest"]["code"], 1)                                   # the phone offers build 1
        self.assertEqual(a["latest"]["package"], "com.proxai.apps.vet_new")
        self.assertEqual(a["held"], "waiting for Nathan's yes")
        code, body, _ = self.get("/v1/apps/vet-new/apk")
        self.assertEqual((code, body), (200, b"APK" + bytes([1]) * 10))            # latest = newest vetted
        code, body, _ = self.get("/v1/apps/vet-new/apk?code=2")
        self.assertEqual((code, json.loads(body)["error"]), (404, "waiting for Nathan's yes"))
        builds = json.loads(self.get("/v1/apps/vet-new")[1])["builds"]
        self.assertEqual([(b["code"], b["available"]) for b in builds], [(1, True), (2, False)])
        self.assertEqual(builds[1]["held"], "waiting for Nathan's yes")
        make_app("vet-none", codes=(1,))                                           # an app with no record at all
        code, body, _ = self.get("/v1/apps/vet-none/apk")
        self.assertEqual((code, json.loads(body)["error"]), (404, "not vetted"))
        self.assertEqual(self._app("vet-none")["held"], "not vetted")

    def test_no_record_nothing_served(self):
        saved = REACH.read_text()
        try:
            for broken in (None, "{half a file", "[]", '"x"'):
                if broken is None:
                    REACH.unlink()
                else:
                    REACH.write_text(broken)
                code, body, _ = self.get("/v1/apps/tip-calc/apk")
                self.assertEqual((code, json.loads(body)["error"]), (404, "not vetted"), broken)
                self.assertEqual(self.get("/v1/apps/tip-calc/apk?code=1")[0], 404)
                a = self._app("tip-calc")
                self.assertNotIn("latest", a)
                self.assertEqual(a["held"], "not vetted")
                self.assertFalse(any(b["available"] for b in json.loads(self.get("/v1/apps/tip-calc")[1])["builds"]))
        finally:
            REACH.write_text(saved)
        self.assertEqual(self.get("/v1/apps/tip-calc/apk")[0], 200)

    # ---- OWNER-SIGN (2026-11-16): Prox Hub and BoardRoom only from the host-signed folder ----

    def _owner_sign(self, app_id, code, data=None):
        """The host's owner-signed copy of build [code], under the same file name as dist/'s."""
        folder = OWNER_SIGNED / app_id
        folder.mkdir(parents=True, exist_ok=True)
        p = folder / f"{app_id}-1.{code}-{code}.apk"
        p.write_bytes(data if data is not None else b"OWNERSIGNED" + bytes([code]) * 7)
        return p

    def test_owner_apps_only_from_the_host_signed_folder(self):
        for app_id in ("prox-hub", "boardroom"):
            shutil.rmtree(OWNER_SIGNED / app_id, ignore_errors=True)        # other tests' copies
            make_app(app_id, codes=(1, 2))
            vet(app_id)                                                    # vetted dist/ files are NOT enough
            code, body, _ = self.get(f"/v1/apps/{app_id}/apk")
            self.assertEqual((code, json.loads(body)["error"]), (404, srv.NOT_OWNER_SIGNED), app_id)
            self.assertEqual(self.get(f"/v1/apps/{app_id}/apk?code=2")[0], 404)
            self.assertNotIn("latest", self._app(app_id))
            self.assertEqual(self._app(app_id)["held"], srv.NOT_OWNER_SIGNED)
            self.assertFalse(any(b["available"] for b in json.loads(self.get(f"/v1/apps/{app_id}")[1])["builds"]))
            signed = self._owner_sign(app_id, 2)
            code, body, _ = self.get(f"/v1/apps/{app_id}/apk")
            self.assertEqual((code, body), (200, signed.read_bytes()), app_id)      # the host-signed bytes, not dist/
            self.assertEqual(self.get(f"/v1/apps/{app_id}/apk?code=2")[1], signed.read_bytes())
            self.assertEqual(self.get(f"/v1/apps/{app_id}/apk?code=1")[0], 404)      # build 1 was never owner-signed
            a = self._app(app_id)
            self.assertEqual(a["latest"]["code"], 2)
            self.assertEqual(a["latest"]["sha256"], sha_of(signed))                  # the phone checks the download
            self.assertEqual(a["latest"]["size"], signed.stat().st_size)
            self.assertIsNone(a["latest"]["cert_sha256"])
            self.assertTrue(a["latest"]["owner_signed"])
            self.assertEqual(a["latest"]["package"], "com.proxai.apps." + app_id.replace("-", "_"))
            rows = json.loads(self.get(f"/v1/apps/{app_id}")[1])["builds"]
            self.assertEqual([(b["code"], b["available"]) for b in rows], [(1, False), (2, True)])
            self.assertEqual(rows[0]["held"], srv.NOT_OWNER_SIGNED)
            self.assertEqual(rows[1]["sha256"], sha_of(signed))

    def test_owner_apps_need_no_dist_vetting_but_nothing_else_comes_from_the_folder(self):
        make_app("prox-hub", codes=(1,))
        RECORD.pop("prox-hub", None)
        REACH.write_text(json.dumps(RECORD))                               # no vetted record for it at all
        signed = self._owner_sign("prox-hub", 1)
        self.assertEqual(self.get("/v1/apps/prox-hub/apk")[1], signed.read_bytes())
        # another app's folder there is never used: other apps still come from dist/ when vetted
        make_app("tip-two", codes=(1,))
        vet("tip-two")
        self._owner_sign("tip-two", 1, b"NOT-THIS")
        self.assertEqual(self.get("/v1/apps/tip-two/apk")[1], b"APK" + bytes([1]) * 10)

    def test_a_package_claiming_an_owner_app_under_another_id_is_not_served(self):
        d = make_app("hub-copy", codes=(1,))
        st = json.loads((d / "status.json").read_text())
        st["last_build"]["package"] = "com.proxai.apps.prox_hub"
        (d / "status.json").write_text(json.dumps(st))
        vet("hub-copy")
        self._owner_sign("hub-copy", 1)
        code, body, _ = self.get("/v1/apps/hub-copy/apk")
        self.assertEqual((code, json.loads(body)["error"]), (404, srv.NOT_OWNER_SIGNED))
        self.assertNotIn("latest", self._app("hub-copy"))
        # the real id with the wrong package serves nothing either
        d = make_app("boardroom", codes=(3,))
        st = json.loads((d / "status.json").read_text())
        st["last_build"]["package"] = "com.proxai.apps.tip_calc"
        (d / "status.json").write_text(json.dumps(st))
        self._owner_sign("boardroom", 3)
        self.assertEqual(self.get("/v1/apps/boardroom/apk?code=3")[0], 404)

    def test_owner_signed_symlinks_are_refused(self):
        make_app("prox-hub", codes=(4,))
        folder = OWNER_SIGNED / "prox-hub"
        folder.mkdir(parents=True, exist_ok=True)
        link = folder / "prox-hub-1.4-4.apk"
        link.unlink(missing_ok=True)
        link.symlink_to(ENV)                                               # a link to the key file
        code, body, _ = self.get("/v1/apps/prox-hub/apk?code=4")
        self.assertEqual(code, 404)
        self.assertNotIn(KEY.encode(), body)
        link.unlink()
        (folder / "prox-hub-1.4-4.apk").mkdir()                            # not a regular file
        self.assertEqual(self.get("/v1/apps/prox-hub/apk?code=4")[0], 404)
        (folder / "prox-hub-1.4-4.apk").rmdir()

    def test_owner_app_with_no_folder_serves_nothing(self):
        make_app("boardroom", codes=(5,))
        shutil.rmtree(OWNER_SIGNED / "boardroom", ignore_errors=True)
        code, body, _ = self.get("/v1/apps/boardroom/apk")
        self.assertEqual((code, json.loads(body)["error"]), (404, srv.NOT_OWNER_SIGNED))

    def test_hashes_are_cached_by_file(self):
        make_app("vet-cache", codes=(1,))
        vet("vet-cache")
        real, calls = srv.hashlib.sha256, []
        srv.hashlib.sha256 = lambda *a: calls.append(1) or real(*a)
        try:
            for _ in range(3):
                self.assertEqual(self.get("/v1/apps/vet-cache/apk")[0], 200)
        finally:
            srv.hashlib.sha256 = real
        self.assertLessEqual(len(calls), 1)                                        # once, then from the cache

    def test_pc_stats_and_alerts(self):
        import time as _t
        from datetime import datetime, timedelta
        THERMAL.mkdir(exist_ok=True)
        now = datetime.now()
        rows = ["time,gpu_c,gpu_w,gpu_use,vram_mib,fan_pct,sm_mhz,throttle,cpu_pkg_c,cpu_core_max_c,cpu_use,ram_used_mb,ram_avail_mb,swap_mb"]
        for i in range(120, 0, -1):   # two minutes of samples, one GPU spike
            t = (now - timedelta(seconds=i)).strftime("%H:%M:%S")
            gpu = 71 if i == 30 else 41
            rows.append(f"{t},{gpu},{40 + i % 3},0,15700,0,1600,0x0,55,60,3.5,14000,{17000 - i},5000")
        (THERMAL / f"{now:%Y-%m-%d}.csv").write_text("\n".join(rows) + "\n")
        (THERMAL / "latest.json").write_text(json.dumps({"time": now.isoformat(), "gpu_c": 41, "limits": {"gpu_warn_c": 75}}))
        (THERMAL / "alerts.jsonl").write_text("".join(
            json.dumps({"id": i, "time": "t", "level": "ALERT", "kind": "gpu_t", "msg": f"GPU alert {i}"}) + "\n"
            for i in range(1, 15)))
        self.assertEqual(self.get("/v1/pc/stats", key=None)[0], 401)
        code, body, _ = self.get("/v1/pc/stats")
        st = json.loads(body)
        self.assertEqual(code, 200)
        self.assertTrue(st["watching"])
        self.assertEqual(st["latest"]["gpu_c"], 41)
        self.assertIn(len(st["history"]), (2, 3))
        self.assertEqual(max(m["gpu_c"] for m in st["history"]), 71)       # the spike survives per-minute peaks
        self.assertEqual([a["id"] for a in st["alerts"]], list(range(5, 15)))
        code, body, _ = self.get("/v1/pc/alerts?after=12")
        self.assertEqual([a["id"] for a in json.loads(body)["alerts"]], [13, 14])
        self.assertEqual(self.get("/v1/pc/alerts?after=x")[0], 400)
        old = _t.time() - 60
        os.utime(THERMAL / "latest.json", (old, old))
        self.assertFalse(json.loads(self.get("/v1/pc/stats")[1])["watching"])   # watcher stopped

    def test_pc_stats_carry_claude_usage(self):
        """P2: Vitals reads Claude's limits and ProxAI's Claude spend from the same pcStats() call."""
        import time as _t
        self.assertIsNone(json.loads(self.get("/v1/pc/stats")[1])["claude"])     # collector not set up yet
        CLAUDE_USAGE.mkdir(exist_ok=True)
        usage = {"version": 1, "updated": int(_t.time()) - 120, "limits_updated": int(_t.time()) - 120,
                 "limits": [{"id": "session", "label": "Current session", "short": "Session", "used_pct": 57,
                             "resets_at": int(_t.time()) + 3000, "severity": "normal", "active": True, "stale": False},
                            {"id": "fable", "label": "Current week (Fable)", "short": "Fable", "used_pct": 10,
                             "resets_at": int(_t.time()) + 90000, "severity": "normal", "active": False, "stale": False}],
                 "limits_error": "", "spend": {"currency": "USD", "today": 0.03, "week": 5.69, "month": 23.0},
                 "spend_error": ""}
        (CLAUDE_USAGE / "usage.json").write_text(json.dumps(usage))
        code, body, _ = self.get("/v1/pc/stats")
        c = json.loads(body)["claude"]
        self.assertEqual(code, 200)
        self.assertIn(c.pop("age_s"), (120, 121, 122))
        self.assertEqual(c, usage)                                                  # passed through as written
        self.assertEqual(self.get("/v1/pc/stats", key=None)[0], 401)
        (CLAUDE_USAGE / "usage.json").write_text("{half a file")
        self.assertIsNone(json.loads(self.get("/v1/pc/stats")[1])["claude"])

    def test_pc_notices(self):
        self.assertEqual(json.loads(self.get("/v1/pc/notices?after=0")[1]), {"notices": []})
        NOTICES.mkdir(exist_ok=True)
        (NOTICES / "notices.jsonl").write_text("".join(json.dumps(
            {"id": i, "time": "t", "kind": "app", "title": f"n{i}", "text": "x"}) + "\n" for i in (1, 2, 3)) + "junk\n")
        r = json.loads(self.get("/v1/pc/notices?after=1")[1])
        self.assertEqual([n["title"] for n in r["notices"]], ["n2", "n3"])
        self.assertEqual(self.get("/v1/pc/notices?after=x")[0], 400)
        self.assertEqual(self.get("/v1/pc/notices", key="wrong")[0], 401)

    def test_pc_notices_pass_tier_through(self):
        # VAULT-UNDO-APP+ (#51): the phone treats tier "thread" as chat only.
        NOTICES.mkdir(exist_ok=True)
        recs = [
            {"id": 1, "time": "t", "kind": "pipeline", "title": "x", "text": "b", "tier": "thread"},
            {"id": 2, "time": "t", "kind": "pipeline", "title": "x", "text": "b"},
            {"id": 3, "time": "t", "kind": "pipeline", "title": "x", "text": "b", "tier": ["thread"]},
            {"id": 4, "time": "t", "kind": "pipeline", "title": "x", "text": "b", "tier": "t" * 100},
        ]
        (NOTICES / "notices.jsonl").write_text("".join(json.dumps(r) + "\n" for r in recs))
        got = {n["id"]: n for n in json.loads(self.get("/v1/pc/notices?after=0")[1])["notices"]}
        self.assertEqual(got[1]["tier"], "thread")
        self.assertNotIn("tier", got[2])
        self.assertNotIn("tier", got[3])                                            # not text: dropped
        self.assertEqual(len(got[4]["tier"]), 20)                                   # capped

    def test_pc_notices_carry_no_quote_decision_and_next(self):
        # NOTICE-QUOTE (0.9.15): what the phone needs to decide what of a notice reaches the model.
        NOTICES.mkdir(exist_ok=True)
        recs = [
            {"id": 1, "time": "t", "kind": "pipeline", "title": "CL1 signed", "text": "record", "no_quote": True},
            {"id": 2, "time": "t", "kind": "pipeline", "title": "Lead: Sam", "text": "wants siding"},
            {"id": 3, "time": "t", "kind": "pipeline", "title": "Lead: Jane", "text": "b",
             "decision": "send the quote", "next": "n" * 1000},
            {"id": 4, "time": "t", "kind": "pipeline", "title": "x", "text": "b",
             "decision": {"stage": "won"}, "next": 5, "no_quote": "yes"},
            {"id": 5, "time": "t", "kind": "rhythm", "title": "y", "text": "b", "no_quote": False, "decision": None},
        ]
        (NOTICES / "notices.jsonl").write_text("".join(json.dumps(r) + "\n" for r in recs))
        got = {n["id"]: n for n in json.loads(self.get("/v1/pc/notices?after=0")[1])["notices"]}
        self.assertIs(got[1]["no_quote"], True)                                     # passed as stored
        self.assertNotIn("no_quote", got[2])                                        # absent stays absent
        self.assertNotIn("decision", got[2])
        self.assertNotIn("next", got[2])
        self.assertEqual(got[3]["decision"], "send the quote")
        self.assertEqual(got[3]["next"], "n" * 300)                                 # capped
        self.assertNotIn("decision", got[4])                                        # not text: dropped
        self.assertNotIn("next", got[4])
        self.assertEqual(got[4]["no_quote"], "yes")                                 # odd values pass; the phone fails closed
        self.assertIs(got[5]["no_quote"], False)
        self.assertNotIn("decision", got[5])
        self.assertEqual(set(got[2]), {"id", "time", "kind", "title", "text"})      # nothing else added

    def test_pc_notices_wait_for_the_next_one(self):
        # B19 (11-02): the listener's long poll returns as soon as a notice lands, and [] after the wait.
        import threading
        import time as _t
        NOTICES.mkdir(exist_ok=True)
        f = NOTICES / "notices.jsonl"
        f.write_text(json.dumps({"id": 7, "time": "t", "kind": "app", "title": "old", "text": "x"}) + "\n")
        started = _t.monotonic()
        self.assertEqual(json.loads(self.get("/v1/pc/notices?after=7&wait=1")[1]), {"notices": []})
        self.assertGreaterEqual(_t.monotonic() - started, 0.9)

        def post_later():
            _t.sleep(0.6)
            with f.open("a") as out:
                out.write(json.dumps({"id": 8, "time": "t", "kind": "calendar", "title": "class in 9 min", "text": "y"}) + "\n")
        threading.Thread(target=post_later).start()
        started = _t.monotonic()
        r = json.loads(self.get("/v1/pc/notices?after=7&wait=20")[1])
        self.assertEqual([n["title"] for n in r["notices"]], ["class in 9 min"])
        self.assertLess(_t.monotonic() - started, 5)
        # Already there: no wait at all.
        started = _t.monotonic()
        self.assertEqual(len(json.loads(self.get("/v1/pc/notices?after=0&wait=20")[1])["notices"]), 2)
        self.assertLess(_t.monotonic() - started, 1)
        self.assertEqual(self.get("/v1/pc/notices?after=0&wait=x")[0], 400)

    def test_approvals(self):
        # F1 (11-02): the question a Hermes yes-gate waits on, for the phone's Accept / Reject pop-up.
        import time as _t
        self.assertEqual(json.loads(self.get("/v1/approvals?session=android-a")[1]), {"approval": None})
        APPROVALS.mkdir(exist_ok=True)
        now = _t.time()
        (APPROVALS / "android-a.json").write_text(json.dumps({
            "id": "abc123", "session": "android-a", "plugin": "proxai-mode", "question": "Switch to SERVER mode?",
            "detail": "That will: ...", "created": now - 5, "expires": now + 600}))
        (APPROVALS / "android-b.json").write_text(json.dumps({
            "id": "def456", "session": "android-b", "plugin": "proxai-apps", "question": "Delete Pong from the PC?",
            "detail": "", "created": now - 1, "expires": now + 300}))
        (APPROVALS / "android-old.json").write_text(json.dumps({
            "id": "old", "session": "android-old", "plugin": "proxai-apps", "question": "Expired?",
            "created": now - 900, "expires": now - 300}))
        r = json.loads(self.get("/v1/approvals?session=android-a")[1])["approval"]
        self.assertEqual((r["id"], r["question"], r["plugin"]), ("abc123", "Switch to SERVER mode?", "proxai-mode"))
        r = json.loads(self.get("/v1/approvals?session=android-a&session=android-b")[1])["approval"]
        self.assertEqual(r["id"], "def456")                               # the newest of his chats
        self.assertIsNone(json.loads(self.get("/v1/approvals?session=android-old")[1])["approval"])
        self.assertEqual(self.get("/v1/approvals")[0], 400)
        self.assertEqual(self.get("/v1/approvals?session=../x%20y")[0], 400)
        self.assertEqual(self.get("/v1/approvals?session=android-a", key="wrong")[0], 401)

    def test_pc_context(self):
        self.assertEqual(json.loads(self.get("/v1/pc/context?session=android-a")[1]), {"match": None})
        CONTEXT.mkdir(exist_ok=True)
        import time as _t
        now = _t.strftime("%Y-%m-%dT%H:%M:%S")
        a = {"session": "android-a", "prompt_tokens": 32662, "context_length": 65536, "time": now}
        b = {"session": "android-b", "prompt_tokens": 9000, "context_length": 65536, "time": now}
        (CONTEXT / "sessions.json").write_text(json.dumps({"latest": b, "sessions": {"android-a": a, "android-b": b}}))
        r = json.loads(self.get("/v1/pc/context?session=android-a")[1])
        self.assertEqual((r["prompt_tokens"], r["match"]), (32662, "session"))
        self.assertLessEqual(r["age_s"], 5)
        r = json.loads(self.get("/v1/pc/context?session=renamed-after-compaction")[1])
        self.assertEqual((r["session"], r["match"]), ("android-b", "latest"))
        self.assertEqual(self.get("/v1/pc/context?session=../x%20y")[0], 400)
        self.assertEqual(self.get("/v1/pc/context?session=android-a", key="wrong")[0], 401)

    def test_real_builds_if_given(self):
        if not os.environ.get("APPS_FROM"):
            self.skipTest("APPS_FROM not set")
        for d in Path(os.environ["APPS_FROM"]).iterdir():
            if d.is_dir() and not d.name.startswith("."):
                code, body, _ = self.get(f"/v1/apps/{d.name}/apk")
                self.assertEqual(code, 200, d.name)
                self.assertEqual(body[:2], b"PK", d.name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
