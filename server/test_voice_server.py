"""VOICE-RELAY: server/voice_server.py relays to a stand-in voice door on a temp Unix socket.

Where Unix sockets can't be made (an agent's sandbox), the stand-in door listens on 127.0.0.1 and
only this test points the relay's connect() at it; everything above connect() is the same code.
Run: python3 -I server/test_voice_server.py
"""
import ast
import http.client
import json
import os
import re
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import voice_server as vs  # noqa: E402

SRC = (HERE / "voice_server.py").read_text()
TOKEN = "Bearer <k3y-with-angles_and.dots~" + "x" * 20 + ">"
MP3 = b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\xff\xfb" * 50
WHICH_ERROR = 'which must be "app"'  # the real door's 400 for a /speak without it


def _temp_dir() -> tempfile.TemporaryDirectory:
    return tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))


def _unix_sockets_work() -> bool:
    """A real bind + listen + connect on a socket in a temp dir made exactly like the tests' own.
    socket() alone isn't enough: some sandboxes allow it but refuse bind or connect, and a long
    TMPDIR can push the path past the AF_UNIX length limit."""
    with _temp_dir() as d:
        path = os.path.join(d, "voice.sock")
        srv = cli = None
        try:
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            srv.bind(path)
            srv.listen(1)
            cli = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            cli.settimeout(2)
            cli.connect(path)
            conn, _ = srv.accept()
            conn.close()
            return True
        except OSError:
            return False
        finally:
            for s in (cli, srv):
                if s is not None:
                    s.close()


UNIX = _unix_sockets_work()
TCP_DOORS: dict[str, int] = {}  # door path -> port, only when UNIX is False

if not UNIX:
    def _connect_tcp(self):
        port = TCP_DOORS.get(self.unix_path)
        if port is None:
            raise FileNotFoundError(self.unix_path)
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=self.timeout)

    vs.DoorConnection.connect = _connect_tcp


class Door:
    """Stand-in for the gateway's voice door: records each request, answers what a test set."""

    def __init__(self, path: str):
        self.path, self.calls = path, []
        self.answer = (200, "audio/mpeg", MP3)
        self.mode = (200, "application/json", json.dumps({"mode": "server", "local_model": "qwen"}).encode())
        self.delay = 0.0
        door = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, status, ctype, data):
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # the relay gave up on a silent door (test_silent_door_is_503)

            def _record(self):
                n = int(self.headers.get("Content-Length") or 0)
                door.calls.append({"method": self.command, "path": self.path,
                                   "auth": self.headers.get("Authorization"),
                                   "body": self.rfile.read(n) if n else b""})
                if door.delay:
                    time.sleep(door.delay)

            def do_GET(self):
                self._record()
                if self.path == "/health":
                    return self._reply(200, "application/json", b'{"status": "ok"}')
                if self.path == "/mode":
                    return self._reply(*door.mode)
                self._reply(404, "application/json", b'{"error": "not found"}')

            def do_POST(self):
                self._record()
                if self.path == "/speak":
                    # Like the real door (VOICE-DOOR door.py speak()): no which="app", no speech.
                    try:
                        body = json.loads(door.calls[-1]["body"] or b"{}")
                    except ValueError:
                        body = None
                    if not isinstance(body, dict):
                        return self._reply(400, "application/json", b'{"error": "body must be JSON"}')
                    if body.get("which") != "app":
                        return self._reply(400, "application/json", json.dumps({"error": WHICH_ERROR}).encode())
                    return self._reply(*door.answer)
                self._reply(404, "application/json", b'{"error": "not found"}')

        if UNIX:
            self.server = socketserver.ThreadingUnixStreamServer(path, H)
        else:
            self.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), H)
            TCP_DOORS[path] = self.server.server_address[1]
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        """Stop the door and take its socket away (the gateway isn't running)."""
        self.server.shutdown()
        self.server.server_close()
        TCP_DOORS.pop(self.path, None)
        if UNIX and os.path.exists(self.path):
            os.unlink(self.path)


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.tmp = _temp_dir()
        self.door_sock = os.path.join(self.tmp.name, "voice.sock")
        os.environ["PROXAI_VOICE_DOOR"] = self.door_sock
        self.door = Door(self.door_sock)
        lsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        lsock.bind(("127.0.0.1", 0))
        lsock.listen(16)
        self.relay = vs.RelayServer(lsock)
        self.port = lsock.getsockname()[1]
        threading.Thread(target=self.relay.serve_forever, daemon=True).start()

    def tearDown(self):
        self.relay.shutdown()
        self.relay.server_close()
        if self.door:
            self.door.close()
        os.environ.pop("PROXAI_VOICE_DOOR", None)
        self.tmp.cleanup()

    def call(self, method, path, body=None, auth=TOKEN, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = dict(headers or {})
        if auth is not None:
            h["Authorization"] = auth
        data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
        c.request(method, path, body=data, headers=h)
        r = c.getresponse()
        out = (r.status, r.getheader("Content-Type"), r.read())
        c.close()
        return out

    def json_of(self, data):
        return json.loads(data)

    # --- /v1/tts --------------------------------------------------------------------------------

    def test_tts_forwards_bearer_unchanged_and_returns_mpeg(self):
        status, ctype, data = self.call("POST", "/v1/tts", {"text": "Hello there.", "voice": "kanye"})
        self.assertEqual((status, ctype, data), (200, "audio/mpeg", MP3))
        [c] = self.door.calls
        self.assertEqual((c["method"], c["path"], c["auth"]), ("POST", "/speak", TOKEN))
        self.assertEqual(json.loads(c["body"]), {"text": "Hello there.", "which": "app"})  # "voice" not passed on

    def test_door_body_is_text_and_which_app_and_nothing_else_from_the_request(self):
        # The app's own "which" or "voice" (or anything else) changes nothing the door receives.
        for extra in ({"which": "door"}, {"which": "app2"}, {"which": "pc", "voice": "kanye"},
                      {"voice": "kanye"}, {"speed": 2, "model": "x", "which": None}):
            self.door.calls.clear()
            status, ctype, _ = self.call("POST", "/v1/tts", {"text": "Hi there.", **extra})
            self.assertEqual((status, ctype), (200, "audio/mpeg"), extra)
            [c] = self.door.calls
            self.assertEqual(json.loads(c["body"]), {"text": "Hi there.", "which": "app"}, extra)

    def test_stand_in_door_refuses_speak_without_which_app_like_the_real_one(self):
        for body in ({"text": "Hi."}, {"text": "Hi.", "which": "pc"}):
            status, _, data = vs.ask_door("POST", "/speak", TOKEN, json.dumps(body).encode())
            self.assertEqual((status, json.loads(data)), (400, {"error": WHICH_ERROR}), body)
        status, ctype, _ = vs.ask_door("POST", "/speak", TOKEN, b'{"text": "Hi.", "which": "app"}',
                                       limit=vs.MAX_AUDIO)
        self.assertEqual((status, ctype), (200, "audio/mpeg"))

    def test_which_is_fixed_in_the_source(self):
        self.assertEqual(vs.DOOR_WHICH, "app")
        self.assertIn('json.dumps({"text": text, "which": DOOR_WHICH})', SRC)

    def test_voice_prefix_from_tailscale_serve(self):
        status, ctype, _ = self.call("POST", "/voice/v1/tts", {"text": "Hi."})
        self.assertEqual((status, ctype), (200, "audio/mpeg"))

    def test_no_or_bad_header_is_401_without_door_call(self):
        for auth in (None, "", "Bearer ", "bearer abc", "Basic abc", "Bearer a b",
                     "Bearer " + "x" * 201, "Bearer \x7fabc"):
            for method, path in (("POST", "/v1/tts"), ("GET", "/v1/mode"), ("POST", "/v1/warmup")):
                status, _, data = self.call(method, path, {"text": "Hi."} if method == "POST" else None, auth=auth)
                self.assertEqual(status, 401, (auth, path))
                self.assertIn("error", self.json_of(data))
        self.assertEqual(self.door.calls, [])

    def test_200_char_token_is_accepted(self):
        tok = "Bearer " + "y" * 200
        self.assertEqual(self.call("POST", "/v1/tts", {"text": "Hi."}, auth=tok)[0], 200)
        self.assertEqual(self.door.calls[0]["auth"], tok)

    def test_door_errors_pass_through(self):
        for code in (400, 401, 429, 502, 503):
            self.door.answer = (code, "application/json", json.dumps({"error": f"door says {code}"}).encode())
            status, ctype, data = self.call("POST", "/v1/tts", {"text": "Hi."})
            self.assertEqual(status, code)
            self.assertEqual(ctype, "application/json")
            self.assertEqual(self.json_of(data), {"error": f"door says {code}"})

    def test_door_error_without_json_gets_a_plain_line(self):
        self.door.answer = (500, "text/html", b"<h1>boom</h1>")
        status, _, data = self.call("POST", "/v1/tts", {"text": "Hi."})
        self.assertEqual((status, self.json_of(data)), (500, {"error": "ProxAI answered HTTP 500"}))

    def test_door_200_that_is_not_audio_is_502(self):
        self.door.answer = (200, "application/json", b'{"ok": true}')
        self.assertEqual(self.call("POST", "/v1/tts", {"text": "Hi."})[0], 502)

    def test_body_over_16kb_is_413_without_door_call(self):
        big = json.dumps({"text": "a" * 100, "pad": "p" * (16 * 1024)}).encode()
        status, _, _ = self.call("POST", "/v1/tts", big)
        self.assertEqual(status, 413)
        self.assertEqual(self.door.calls, [])

    def test_text_limits(self):
        status, _, data = self.call("POST", "/v1/tts", {"text": "a" * 1201})
        self.assertEqual(status, 400)
        self.assertIn("1200", self.json_of(data)["error"])
        self.assertEqual(self.door.calls, [])
        self.assertEqual(self.call("POST", "/v1/tts", {"text": "a" * 1200})[0], 200)

    def test_empty_or_bad_body_is_400(self):
        for body in ({"text": "   "}, {"text": 5}, {}, b"not json", b"[1, 2]"):
            self.assertEqual(self.call("POST", "/v1/tts", body)[0], 400, body)
        self.assertEqual(self.door.calls, [])

    def test_missing_door_is_503(self):
        self.door.close()
        self.door = None
        status, _, data = self.call("POST", "/v1/tts", {"text": "Hi."})
        self.assertEqual((status, self.json_of(data)), (503, {"error": "ProxAI isn't running"}))
        self.assertEqual(self.call("GET", "/v1/mode")[0], 503)

    def test_silent_door_is_503(self):
        old = vs.DOOR_TIMEOUT
        vs.DOOR_TIMEOUT = 0.5
        self.door.delay = 2.0
        try:
            status, _, data = self.call("POST", "/v1/tts", {"text": "Hi."})
        finally:
            vs.DOOR_TIMEOUT = old
        self.assertEqual((status, self.json_of(data)), (503, {"error": "ProxAI isn't running"}))

    def test_door_timeout_is_70_seconds(self):
        self.assertEqual(vs.DOOR_TIMEOUT, 70.0)

    # --- /v1/mode, /v1/warmup, /health ----------------------------------------------------------

    def test_mode_is_the_doors_mode(self):
        status, ctype, data = self.call("GET", "/v1/mode")
        self.assertEqual((status, ctype), (200, "application/json"))
        self.assertEqual(self.json_of(data), {"mode": "server", "local_model": "qwen"})
        [c] = self.door.calls
        self.assertEqual((c["method"], c["path"], c["auth"]), ("GET", "/mode", TOKEN))

    def test_mode_passes_door_401(self):
        self.door.mode = (401, "application/json", b'{"error": "invalid API key"}')
        status, _, data = self.call("GET", "/v1/mode")
        self.assertEqual((status, self.json_of(data)), (401, {"error": "invalid API key"}))

    def test_warmup_is_202_and_does_nothing(self):
        status, _, _ = self.call("POST", "/v1/warmup", {"voice": "kanye"})
        self.assertEqual(status, 202)
        self.assertEqual(self.door.calls, [])

    def test_health_needs_no_token_and_says_whether_the_door_answers(self):
        status, _, data = self.call("GET", "/health", auth=None)
        self.assertEqual((status, self.json_of(data)), (200, {"status": "ok", "door": True}))
        self.assertIsNone(self.door.calls[0]["auth"])  # nothing to forward, nothing forwarded
        self.door.close()
        self.door = None
        status, _, data = self.call("GET", "/voice/health", auth=None)
        self.assertEqual((status, self.json_of(data)), (200, {"status": "ok", "door": False}))

    def test_unknown_route_is_404(self):
        self.assertEqual(self.call("GET", "/v1/tts")[0], 404)
        self.assertEqual(self.call("POST", "/v1/other", {"text": "x"})[0], 404)
        self.assertEqual(self.door.calls, [])

    def test_log_line_has_no_text_and_no_token(self):
        import contextlib, io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.call("POST", "/v1/tts", {"text": "secret words here"})
            self.call("POST", "/v1/tts", {"text": "hi"}, auth="Bearer " + "z" * 30)
            time.sleep(0.2)
        out = buf.getvalue()
        self.assertNotIn("secret", out)
        self.assertNotIn("k3y", out)
        self.assertNotIn("z" * 30, out)
        self.assertRegex(out, r"POST /v1/tts 200 \d+ms")

    def test_idle_exit(self):
        vs.ACTIVITY.last = time.monotonic() - 10
        t = threading.Thread(target=vs.idle_watcher, args=(self.relay, 1, 0.05))
        t.start()
        t.join(5)
        self.assertFalse(t.is_alive())


class StartUp(unittest.TestCase):
    def env(self, **extra):
        e = {"PATH": "/usr/bin:/bin", "HOME": os.environ.get("TMPDIR", "/tmp")}
        e.update(extra)
        return e

    def test_without_listen_fds_it_exits_non_zero(self):
        r = subprocess.run([sys.executable, "-I", str(HERE / "voice_server.py")], env=self.env(),
                           capture_output=True, text=True, timeout=20)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("LISTEN_FDS", r.stderr)

    def test_listen_fds_for_another_pid_is_refused(self):
        r = subprocess.run([sys.executable, "-I", str(HERE / "voice_server.py")],
                           env=self.env(LISTEN_PID="1", LISTEN_FDS="1"), capture_output=True, timeout=20)
        self.assertNotEqual(r.returncode, 0)

    def test_serves_on_the_socket_systemd_hands_in(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as tmp:
            door_sock = os.path.join(tmp, "voice.sock")
            door = Door(door_sock)
            lsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            lsock.bind(("127.0.0.1", 0))
            lsock.listen(4)
            port = lsock.getsockname()[1]
            fd = lsock.fileno()
            # Like systemd: the listener on fd 3, LISTEN_PID = the server's own pid (exec keeps it).
            script = f'exec 3<&{fd}; export LISTEN_PID=$$ LISTEN_FDS=1; exec "$0" -I "$1"'
            p = subprocess.Popen(["bash", "-c", script, sys.executable, str(HERE / "voice_server.py")],
                                 env=self.env(PROXAI_VOICE_DOOR=door_sock), pass_fds=(fd,),
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
                c.request("GET", "/health")
                r = c.getresponse()
                # Without Unix sockets the child can't reach the stand-in door: it says so, still ok.
                self.assertEqual((r.status, json.loads(r.read())), (200, {"status": "ok", "door": UNIX}))
                c.close()
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
                c.request("POST", "/v1/tts", body=b'{"text": "Hi."}')
                self.assertEqual(c.getresponse().status, 401)
                c.close()
                if UNIX:
                    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
                    c.request("POST", "/v1/tts", body=b'{"text": "Hi."}', headers={"Authorization": TOKEN})
                    r = c.getresponse()
                    self.assertEqual((r.status, r.getheader("Content-Type"), r.read()), (200, "audio/mpeg", MP3))
                    c.close()
            finally:
                p.terminate()
                p.communicate(timeout=10)
                lsock.close()
                door.close()


class Source(unittest.TestCase):
    def test_standard_library_only(self):
        mods = set()
        for node in ast.walk(ast.parse(SRC)):
            if isinstance(node, ast.Import):
                mods |= {a.name.split(".")[0] for a in node.names}
            elif isinstance(node, ast.ImportFrom) and node.level == 0:
                mods.add(node.module.split(".")[0])
        self.assertEqual(mods - set(sys.stdlib_module_names) - {"__future__"}, set())

    def test_no_env_file_and_only_the_door_under_the_state_folder(self):
        self.assertIsNone(re.search(r"\.env\b", SRC))
        self.assertFalse("API_SERVER_KEY" in SRC)
        self.assertEqual(re.findall(r"\.local/state[^\s\"',]*", SRC), [".local/state/proxai/voice/api/voice.sock"] * 2)
        for word in ("open(", "read_text", "read_bytes", "mode.json", "chatterbox", "tts_pipeline", "torch"):
            self.assertFalse(word in SRC.lower(), word)

    def test_never_opens_a_network_socket(self):
        self.assertFalse("AF_INET" in SRC, "AF_INET")
        self.assertFalse(".bind(" in SRC, ".bind(")
        self.assertFalse("create_connection" in SRC, "create_connection")
        self.assertFalse("TCPServer.__init__(" in SRC, "TCPServer.__init__(")  # it would make a new AF_INET socket


if __name__ == "__main__":
    unittest.main()
