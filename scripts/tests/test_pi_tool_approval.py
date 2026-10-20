"""Run the shared Pi authorization regressions instead of the retired Git wrapper checks."""

import pathlib
import shutil
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SUITE = ROOT / "features/ai-engine/pi/impl/src/jvmTest/resources/pi/heartbeat-approval.test.mjs"


class PiToolApprovalTest(unittest.TestCase):
    def test_shared_authorization_contract(self):
        node = shutil.which("node")
        if node is None:
            self.skipTest("Node.js 22.18+ is needed to execute the TypeScript extension")
        version = subprocess.check_output([node, "--version"], text=True).strip().lstrip("v")
        if tuple(int(part) for part in version.split(".")[:2]) < (22, 18):
            self.skipTest("Node.js 22.18+ is needed to execute the TypeScript extension")
        result = subprocess.run(
            [node, "--test", str(SUITE)], capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
