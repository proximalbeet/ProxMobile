"""Tests for proxai_thermal_watch.py's alert numbering and GPU protection (fake Ollama, no GPU load).

Run: python3 server/test_thermal_watch.py
"""
from __future__ import annotations

import importlib.util
import json
import os
import shutil
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
TMP = Path(tempfile.mkdtemp(prefix="thermal-test-", dir=os.environ.get("TMPDIR")))
CALLS: list = []


class FakeOllama(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        CALLS.append(("GET", self.path, None))
        self._send({"models": [{"name": "qwen3.5:35b-a3b"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        CALLS.append(("POST", self.path, body))
        self._send({"done": True})


SRV = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
threading.Thread(target=SRV.serve_forever, daemon=True).start()
os.environ.update(PROXAI_THERMAL_DIR=str(TMP), OLLAMA_HOST=f"http://127.0.0.1:{SRV.server_port}")


def load():
    spec = importlib.util.spec_from_file_location("tw", HERE / "proxai_thermal_watch.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class ThermalWatch(unittest.TestCase):
    @classmethod
    def tearDownClass(cls):
        SRV.shutdown()
        shutil.rmtree(TMP, ignore_errors=True)

    def test_protect_unloads_once_per_5_minutes(self):
        tw = load()
        tw.protect_gpu("GPU reached 83C")
        tw.protect_gpu("GPU reached 84C")
        self.assertEqual(CALLS, [("GET", "/api/ps", None),
                                 ("POST", "/api/generate", {"model": "qwen3.5:35b-a3b", "keep_alive": 0})])
        last = json.loads((TMP / "alerts.jsonl").read_text().splitlines()[-1])
        self.assertEqual((last["level"], last["kind"]), ("CRIT", "protect"))
        self.assertIn("unloaded local model (qwen3.5:35b-a3b) because GPU reached 83C", last["msg"])

    def test_alert_ids_continue_after_a_restart(self):
        tw = load()
        tw.alert("ALERT", "ram", "RAM low: 1900 MB available")
        before = [json.loads(l)["id"] for l in (TMP / "alerts.jsonl").read_text().splitlines()]
        tw2 = load()   # a new process
        tw2.alert("ALERT", "swap", "swap grew")
        ids = [json.loads(l)["id"] for l in (TMP / "alerts.jsonl").read_text().splitlines()]
        self.assertEqual(ids, before + [before[-1] + 1])
        self.assertEqual(ids, sorted(set(ids)))

    def test_latest_json(self):
        from datetime import datetime
        tw = load()
        tw.write_latest(datetime(2026, 10, 22, 23, 0, 0), {"gpu_c": 41.0, "cpu_pkg_c": 55.0}, 16303.0)
        d = json.loads((TMP / "latest.json").read_text())
        self.assertEqual((d["time"], d["gpu_c"], d["vram_total_mib"]), ("2026-10-22T23:00:00", 41.0, 16303.0))
        self.assertEqual(d["limits"]["gpu_crit_c"], 82)


if __name__ == "__main__":
    unittest.main(verbosity=2)
