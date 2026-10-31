"""The pinned builder must reject unexpected source changes before applying or building anything."""
import subprocess
import os
import shutil
import tempfile
import unittest
from pathlib import Path

import build_runtime


class SourceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="heartbeat-kimi-builder-")
        self.source = Path(self.temporary.name)
        self.git("init", "-q")
        (self.source / "source.ts").write_text("original", encoding="utf-8")
        self.git("add", "source.ts")
        self.git("-c", "user.name=Runtime test", "-c", "user.email=runtime@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        self.commit = self.git("rev-parse", "HEAD").strip()

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.source, check=True, capture_output=True,
                              text=True, encoding="utf-8").stdout

    def test_unexpected_tracked_change_is_rejected(self):
        (self.source / "source.ts").write_text("injected", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "Unexpected changes"):
            build_runtime.prepare(self.source, {"commit": self.commit}, "unused")

    def test_unexpected_untracked_source_is_rejected(self):
        (self.source / "injected.ts").write_text("injected", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "Unexpected changes"):
            build_runtime.prepare(self.source, {"commit": self.commit}, "unused")

    def test_other_commit_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "not the locked commit"):
            build_runtime.prepare(self.source, {"commit": "0" * 40}, "unused")


class LoginFlowTest(unittest.TestCase):
    def test_pinned_login_flow_never_opens_browser_before_host_validation(self):
        original = ("import { openUrl } from '#/utils/open-url';\n"
                    "Opening browser for Kimi device login: "
                    "        try {\n          openUrl(url);\n        } catch {\n"
                    "          // Best effort only: the manual fallback has already been printed.\n        }")
        result = build_runtime.patch_login_flow(original)
        self.assertNotIn("openUrl", result)
        self.assertIn("Kimi device login URL: ", result)


class RuntimeArchTest(unittest.TestCase):
    def test_supported_platform_architectures(self):
        for machine, expected in [("AMD64", "x64"), ("x86_64", "x64"), ("ARM64", "arm64"), ("aarch64", "arm64")]:
            with self.subTest(machine=machine):
                self.assertEqual(expected, build_runtime.runtime_arch(machine))


class BuildEnvironmentTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "Node is required for the nested command regression")
    def test_nested_shell_resolves_verified_pnpm_with_arguments(self):
        with tempfile.TemporaryDirectory(prefix="kimi tools '") as temporary:
            directory = Path(temporary)
            pnpm = directory / "pinned pnpm.cjs"
            pnpm.write_text("console.log(JSON.stringify(process.argv.slice(2)))", encoding="utf-8")
            node = Path(shutil.which("node")).resolve()
            env = build_runtime.build_environment(node, pnpm, directory / "bin", "locked-commit")
            # Match upstream: Node spawns a shell which resolves pnpm through PATH.
            result = subprocess.run([node, "-e",
                "require('node:child_process').execSync('pnpm --filter @moonshot-ai/vis-web exec vite build', {stdio:'inherit'})"],
                env=env, check=True, capture_output=True, text=True)
            self.assertEqual('["--filter","@moonshot-ai/vis-web","exec","vite","build"]', result.stdout.strip())
            self.assertEqual("locked-commit", env["KIMI_CODE_COMMIT"])
            self.assertEqual("0", env["HUSKY"])
            self.assertEqual(str(directory / "bin"), env["PATH"].split(os.pathsep)[0])


if __name__ == "__main__":
    unittest.main()
