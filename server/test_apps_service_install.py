"""OWNER-SIGNED-DIR: the owner-signed folder exists before the apps unit installs or starts.

Reads the script and unit as text, and runs the script's mkdir line against a temp HOME.
Run: python3 server/test_apps_service_install.py
"""
import os, re, subprocess, tempfile, unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SH = (HERE.parent / "scripts" / "install-apps-service.sh").read_text()
UNIT = (HERE / "proxai-apps.service").read_text()
MK = 'mkdir -p -m 700 "$HOME/.local/share/proxai-owner-signed"'


class OwnerSignedDir(unittest.TestCase):
    def test_script_creates_folder_before_install_and_start(self):
        i = SH.index(MK)
        self.assertLess(i, SH.index('install -m 0644 "$REPO/server/$SERVICE"'))
        self.assertLess(i, SH.index("systemctl --user restart"))

    def test_unit_execstartpre_before_execstart(self):
        m = re.search(r"^ExecStartPre=\+/usr/bin/mkdir -p -m 0700 %h/\.local/share/proxai-owner-signed$", UNIT, re.M)
        self.assertTrue(m)
        self.assertLess(m.start(), UNIT.index("ExecStart=/usr/bin"))

    def test_mkdir_line_makes_0700_folder_in_temp_home(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as h:
            subprocess.run(["bash", "-c", MK], env={"HOME": h, "PATH": os.environ["PATH"]}, check=True)
            d = Path(h) / ".local/share/proxai-owner-signed"
            self.assertTrue(d.is_dir())
            self.assertEqual(d.stat().st_mode & 0o777, 0o700)


if __name__ == "__main__":
    unittest.main()
