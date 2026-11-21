"""VOICE-RELAY: the voice unit's own sandbox and the install script, read as text, plus the script
run as a copy inside a temp HOME with a stand-in `systemctl` that only logs (the real one is never
called: nothing on this PC is installed, started or restarted).

Run: python3 -I server/test_voice_service_install.py
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "scripts" / "install-voice-service.sh"
SH = SCRIPT.read_text()
UNIT = (HERE / "proxai-phone-voice.service").read_text()
SOCKET = (HERE / "proxai-phone-voice.socket").read_text()


def directives(text: str) -> list[tuple[str, str]]:
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith(("#", ";", "[")) and "=" in line:
            k, v = line.split("=", 1)
            out.append((k.strip(), v.strip()))
    return out


D = directives(UNIT)


def values(key: str) -> list[str]:
    return [v for k, v in D if k == key]


class Unit(unittest.TestCase):
    def test_execstart_is_isolated_system_python(self):
        self.assertEqual(values("ExecStart"),
                         ["/usr/bin/python3 -I %h/dev/projects/ProxMobile/server/voice_server.py"])

    def test_sandbox_lines(self):
        want = {"PrivateNetwork": "yes", "RestrictAddressFamilies": "AF_UNIX", "ProtectHome": "tmpfs",
                "ProtectSystem": "strict", "PrivateTmp": "yes", "NoNewPrivileges": "yes",
                "ProtectKernelTunables": "yes", "ProtectControlGroups": "yes", "RestrictRealtime": "yes",
                "LockPersonality": "yes", "MemoryMax": "256M"}
        for k, v in want.items():
            self.assertEqual(values(k), [v], k)

    def test_docker_and_tailscale_hidden(self):
        hidden = " ".join(values("InaccessiblePaths")).split()
        for p in ("-/run/docker.sock", "-/var/run/docker.sock", "-/run/tailscale"):
            self.assertIn(p, hidden)

    def test_only_its_code_and_the_door_folder_are_bound(self):
        binds = " ".join(values("BindReadOnlyPaths")).split()
        self.assertEqual(binds, ["%h/dev/projects/ProxMobile/server", "%h/.local/state/proxai/voice/api"])
        self.assertEqual(values("BindPaths"), [])

    def test_no_key_file_no_whole_state_no_voice_folder_no_venv(self):
        body = "\n".join(f"{k}={v}" for k, v in D)
        self.assertIsNone(re.search(r"\.env\b", body))
        for bad in (".hermes", "venv", "NUMBA", "TQDM", "HF_HOME", ".cache", "kanye", "PYTHONPATH"):
            self.assertNotIn(bad, body, bad)
        for b in " ".join(values("BindReadOnlyPaths") + values("BindPaths")).split():
            self.assertNotRegex(b.lstrip("-"), r"^%h/\.local/state/proxai/?$")
            self.assertNotRegex(b.lstrip("-"), r"^%h/\.local/state/proxai/voice/?$")

    def test_no_plus_exec_and_no_temporary_file_system(self):
        self.assertIsNone(re.search(r"^\s*Exec\w*\s*=\s*[-@:!|]*\+", UNIT, re.M))
        self.assertEqual(values("TemporaryFileSystem"), [])

    def test_no_network_target_and_socket_unchanged(self):
        self.assertNotIn("network-online.target", UNIT)
        self.assertIn("Requires=proxai-phone-voice.socket", UNIT)
        self.assertIn("ListenStream=127.0.0.1:8653", SOCKET)


class ScriptText(unittest.TestCase):
    def install_at(self):
        return SH.index('install -m 0644 "$REPO/server/$SERVICE" "$DIR/$SERVICE"')

    def test_saves_makes_door_folder_and_removes_old_sandbox_before_installing(self):
        save = SH.index('cp -p "$DIR/$SERVICE" "$SAVED/$SERVICE"')
        mk = SH.index('mkdir -p -m 700 "$DOOR_DIR"')
        rm = SH.index('rm -f "$OLD_SANDBOX"')
        self.assertLess(save, mk)
        self.assertLess(mk, rm)
        self.assertLess(rm, self.install_at())
        self.assertIn('SAVED="$HOME/.local/state/proxmobile-voice-previous"', SH)
        self.assertIn('chmod 700 "$SAVED"', SH)
        self.assertIn('OLD_SANDBOX="$DROPINS/40-sandbox.conf"', SH)
        for line in SH.splitlines():  # the mode switch's drop-in is never removed
            if re.search(r"\brm\b", line):
                self.assertNotIn("50-proxai-mode", line)
                self.assertNotRegex(line, r'"\$DROPINS"?\s*$')

    def test_has_rollback(self):
        self.assertIn('"${1:-}" == "--rollback"', SH)
        rb = SH[SH.index('"--rollback" ]]'):]
        self.assertIn('install -m 0644 "$SAVED/$SERVICE" "$DIR/$SERVICE"', rb)
        self.assertIn('"$SAVED/40-sandbox.conf" "$OLD_SANDBOX"', rb)

    def test_prints_his_three_checks(self):
        self.assertIn("systemctl --user show proxai-phone-voice -p PrivateNetwork -p DropInPaths", SH)
        self.assertIn("curl -s http://127.0.0.1:8653/health", SH)
        self.assertIn("readlink /proc/$(systemctl --user show -p MainPID --value proxai-phone-voice)/ns/net "
                      "/proc/self/ns/net", SH)

    def test_bash_syntax(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)


OLD_UNIT = "[Service]\nExecStart=/venv/python %h/dev/projects/ProxMobile/server/kanye_voice_server.py\n"
OLD_SOCKET = "[Socket]\nListenStream=127.0.0.1:8653\n"
OLD_SANDBOX = "[Service]\nTemporaryFileSystem=%h\n"
MODE_DROPIN = "[Service]\nEnvironment=PROXAI_MODE=server\n"


class ScriptRun(unittest.TestCase):
    """A copy of the script in a temp HOME; systemctl is a stand-in that writes its arguments to a log."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.home = Path(self.tmp.name)
        self.repo = self.home / "dev/projects/ProxMobile"
        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "server").mkdir()
        shutil.copy(SCRIPT, self.repo / "scripts" / SCRIPT.name)
        for f in ("proxai-phone-voice.service", "proxai-phone-voice.socket"):
            shutil.copy(HERE / f, self.repo / "server" / f)
        (self.repo / "server/kanye_voice_server.py").write_text("# old server\n")
        self.units = self.home / ".config/systemd/user"
        self.dropins = self.units / "proxai-phone-voice.service.d"
        self.dropins.mkdir(parents=True)
        (self.units / "proxai-phone-voice.service").write_text(OLD_UNIT)
        (self.units / "proxai-phone-voice.socket").write_text(OLD_SOCKET)
        (self.dropins / "40-sandbox.conf").write_text(OLD_SANDBOX)
        (self.dropins / "50-proxai-mode.conf").write_text(MODE_DROPIN)
        self.bin = self.home / "fakebin"
        self.bin.mkdir()
        fake = self.bin / "systemctl"
        fake.write_text('#!/bin/sh\necho "$@" >> "$HOME/systemctl.log"\n')
        fake.chmod(0o755)
        self.saved = self.home / ".local/state/proxmobile-voice-previous"
        self.door = self.home / ".local/state/proxai/voice/api"

    def tearDown(self):
        self.tmp.cleanup()

    def run_script(self, *args):
        env = {"HOME": str(self.home), "PATH": f"{self.bin}:/usr/bin:/bin"}
        return subprocess.run(["bash", str(self.repo / "scripts" / SCRIPT.name), *args], env=env,
                              capture_output=True, text=True, timeout=30)

    def test_install_saves_old_files_and_installs_the_relay(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.saved.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.saved / "proxai-phone-voice.service").read_text(), OLD_UNIT)
        self.assertEqual((self.saved / "proxai-phone-voice.socket").read_text(), OLD_SOCKET)
        self.assertEqual((self.saved / "40-sandbox.conf").read_text(), OLD_SANDBOX)
        self.assertEqual((self.saved / "kanye_voice_server.py").read_text(), "# old server\n")
        self.assertEqual((self.units / "proxai-phone-voice.service").read_text(), UNIT)
        self.assertFalse((self.dropins / "40-sandbox.conf").exists())
        self.assertEqual((self.dropins / "50-proxai-mode.conf").read_text(), MODE_DROPIN)
        self.assertTrue(self.door.is_dir())
        self.assertEqual(self.door.stat().st_mode & 0o777, 0o700)
        log = (self.home / "systemctl.log").read_text()
        self.assertIn("--user daemon-reload", log)
        self.assertIn("--user restart proxai-phone-voice.socket", log)
        self.assertIn("PrivateNetwork -p DropInPaths", r.stdout)

    def test_rerun_keeps_the_pre_relay_copies(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual((self.saved / "proxai-phone-voice.service").read_text(), OLD_UNIT)
        self.assertEqual((self.saved / "40-sandbox.conf").read_text(), OLD_SANDBOX)

    def test_existing_door_folder_is_left_alone(self):
        self.door.mkdir(parents=True, mode=0o750)
        self.door.chmod(0o750)
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.door.stat().st_mode & 0o777, 0o750)

    def test_rollback_puts_the_saved_files_back(self):
        self.assertEqual(self.run_script().returncode, 0)
        (self.repo / "server/kanye_voice_server.py").unlink()  # as after the merge
        r = self.run_script("--rollback")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.units / "proxai-phone-voice.service").read_text(), OLD_UNIT)
        self.assertEqual((self.units / "proxai-phone-voice.socket").read_text(), OLD_SOCKET)
        self.assertEqual((self.dropins / "40-sandbox.conf").read_text(), OLD_SANDBOX)
        self.assertEqual((self.repo / "server/kanye_voice_server.py").read_text(), "# old server\n")
        log = (self.home / "systemctl.log").read_text().splitlines()
        self.assertEqual(log[-1], "--user restart proxai-phone-voice.socket")

    def test_rollback_without_saved_files_fails(self):
        r = self.run_script("--rollback")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual((self.units / "proxai-phone-voice.service").read_text(), OLD_UNIT)

    def test_unknown_option_changes_nothing(self):
        r = self.run_script("--rolback")
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse(self.saved.exists())
        self.assertTrue((self.dropins / "40-sandbox.conf").exists())

    def test_refuses_outside_the_main_checkout(self):
        other = self.home / "elsewhere"
        shutil.copytree(self.repo, other)
        env = {"HOME": str(self.home), "PATH": f"{self.bin}:/usr/bin:/bin"}
        r = subprocess.run(["bash", str(other / "scripts" / SCRIPT.name)], env=env, capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse(self.saved.exists())


if __name__ == "__main__":
    unittest.main()
