"""Check the installer size gate."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_installer_size", ROOT / "scripts" / "check_installer_size.py")
check_installer_size = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_installer_size)


class InstallerSizeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def installer(self, size: int) -> str:
        path = Path(self.directory.name) / "Heartbeat.dmg"
        path.write_bytes(b"\0" * size)
        return str(path)

    def test_default_limit_is_fifty_mebibytes(self):
        self.assertEqual(check_installer_size.DEFAULT_LIMIT_BYTES, 52428800)

    def test_file_at_limit_passes(self):
        self.assertEqual(check_installer_size.main([self.installer(1000), "--limit-bytes", "1000"]), 0)

    def test_file_over_limit_fails(self):
        self.assertEqual(check_installer_size.main([self.installer(1001), "--limit-bytes", "1000"]), 1)

    def test_missing_file_fails(self):
        missing = str(Path(self.directory.name) / "absent.exe")
        self.assertEqual(check_installer_size.main([missing]), 1)


if __name__ == "__main__":
    unittest.main()
