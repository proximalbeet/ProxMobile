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


class AppsServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        APPS.mkdir(parents=True)
        ENV.write_text(f"API_SERVER_KEY={KEY}  # comment\n")
        cls.server = srv.ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        make_app()
        (APPS / ".signing").mkdir()
        (APPS / ".signing" / "password").write_text("secret")
        if os.environ.get("APPS_FROM"):
            for d in Path(os.environ["APPS_FROM"]).iterdir():
                if d.is_dir() and not d.name.startswith("."):
                    shutil.copytree(d, APPS / d.name)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        shutil.rmtree(TMP, ignore_errors=True)

    def get(self, path, key=KEY):
        req = urllib.request.Request(self.base + path, headers={"Authorization": f"Bearer {key}"} if key else {})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

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

    def test_apk_entry_pointing_outside_dist_refused(self):
        d = make_app("evil-app", codes=(1,))
        b = json.loads((d / "builds.json").read_text())
        b[0]["apk"] = "../../.signing/password"
        (d / "builds.json").write_text(json.dumps(b))
        self.assertEqual(self.get("/v1/apps/evil-app/apk")[0], 404)

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

    def test_pc_notices(self):
        self.assertEqual(json.loads(self.get("/v1/pc/notices?after=0")[1]), {"notices": []})
        NOTICES.mkdir(exist_ok=True)
        (NOTICES / "notices.jsonl").write_text("".join(json.dumps(
            {"id": i, "time": "t", "kind": "app", "title": f"n{i}", "text": "x"}) + "\n" for i in (1, 2, 3)) + "junk\n")
        r = json.loads(self.get("/v1/pc/notices?after=1")[1])
        self.assertEqual([n["title"] for n in r["notices"]], ["n2", "n3"])
        self.assertEqual(self.get("/v1/pc/notices?after=x")[0], 400)
        self.assertEqual(self.get("/v1/pc/notices", key="wrong")[0], 401)

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
