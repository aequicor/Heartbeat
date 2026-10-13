"""Check release selection and the workflow's tag creation without publishing."""

import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release_version.py"
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
spec = importlib.util.spec_from_file_location("release_version", SCRIPT)
release_version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_version)


class ReleaseVersionTests(unittest.TestCase):
    def test_levels_reset_less_significant_parts(self):
        for level, expected in (("patch", "2.8.5"), ("minor", "2.9.0"), ("major", "3.0.0")):
            with self.subTest(level=level):
                self.assertEqual(release_version.next_version(["v2.8.4"], "1.0.0", level), expected)

    def test_highest_numeric_stable_tag_wins(self):
        tags = ["v2.9.9", "v2.10.1", "v2.8.100", "v3.0.0-rc1", "jcef-100", "v02.11.0"]
        self.assertEqual(release_version.next_version(tags, "1.0.0", "patch"), "2.10.2")

    def test_first_release_bumps_gradle_baseline(self):
        for level, expected in (("patch", "1.0.1"), ("minor", "1.1.0"), ("major", "2.0.0")):
            with self.subTest(level=level):
                self.assertEqual(release_version.next_version([], "1.0.0", level), expected)

    def test_installer_limits_do_not_silently_roll_over(self):
        for baseline, level in (("1.0.999", "patch"), ("1.999.3", "minor"), ("999.2.3", "major")):
            with self.subTest(baseline=baseline, level=level), self.assertRaises(ValueError):
                release_version.next_version([], baseline, level)
        self.assertEqual(release_version.next_version([], "1.0.999", "minor"), "1.1.0")
        self.assertEqual(release_version.next_version([], "1.999.999", "major"), "2.0.0")

    def test_bad_baseline_is_rejected(self):
        for baseline in ("0.1.0", "1000.0.0", "1.1000.0", "1.0.1000", "1.2", "01.2.3", "1.2.3\n", "$(id)"):
            with self.subTest(baseline=baseline), self.assertRaises(ValueError):
                release_version.next_version([], baseline, "patch")

    def test_unsupported_stable_tag_is_not_ignored(self):
        with self.assertRaises(ValueError):
            release_version.next_version(["v1000.0.0"], "1.0.0", "patch")


@unittest.skipUnless(shutil.which("git"), "requires Git")
class ReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release@example.invalid")
        self.git("commit", "--allow-empty", "-qm", "Test release")
        (self.directory / "gradle.properties").write_text("heartbeat.version=1.0.0\n")

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.directory, check=True, capture_output=True, text=True
        ).stdout.strip()

    def plan(self, level="patch", ref="refs/heads/master", dry_run="false"):
        return subprocess.run(
            [sys.executable, str(SCRIPT), f"--level={level}", f"--ref={ref}", f"--dry-run={dry_run}"],
            cwd=self.directory, capture_output=True, text=True, check=False,
        )

    def test_cli_outputs_selected_version_and_tag_without_creating_tag(self):
        self.git("tag", "v1.2.3")
        result = self.plan(level="minor")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "version=1.3.0\ntag=v1.3.0\n")
        self.assertEqual(self.git("tag"), "v1.2.3")

    def test_branch_and_tag_publications_are_rejected_but_dry_run_is_allowed(self):
        for ref in ("refs/heads/work", "refs/tags/v1.2.3", "master"):
            with self.subTest(ref=ref):
                self.assertNotEqual(self.plan(ref=ref).returncode, 0)
                self.assertEqual(self.plan(ref=ref, dry_run="true").returncode, 0)

    def test_invalid_and_injected_inputs_fail_without_outputs(self):
        for level in ("", "PATCH", "patch\nminor", "$(touch injected)", "; touch injected", "--help"):
            with self.subTest(level=level):
                result = self.plan(level=level)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
        for dry_run in ("", "1", "FALSE", "false; touch injected"):
            with self.subTest(dry_run=dry_run):
                self.assertNotEqual(self.plan(dry_run=dry_run).returncode, 0)
        self.assertFalse((self.directory / "injected").exists())

    @unittest.skipIf(os.name == "nt" or not shutil.which("bash"), "requires Unix bash")
    def test_tag_step_creates_tested_sha_and_safely_resumes(self):
        lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
        start = lines.index("      - name: Tag the tested commit")
        start = lines.index("        run: |", start) + 1
        snippet = []
        for line in lines[start:]:
            if line and not line.startswith("          "):
                break
            snippet.append(line[10:])
        gh = self.directory / "gh"
        gh.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$CALLS"\n')
        gh.chmod(0o755)
        calls = self.directory / "calls"
        sha = self.git("rev-parse", "HEAD")
        env = dict(os.environ, PATH=f"{self.directory}{os.pathsep}{os.environ['PATH']}",
                   TAG="v1.0.1", GITHUB_SHA=sha, GH_REPO="owner/repo", CALLS=str(calls))

        def run():
            return subprocess.run(
                ["bash", "-e", "-c", "\n".join(snippet)], cwd=self.directory,
                env=env, capture_output=True, text=True, check=False,
            )

        result = run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.read_text().splitlines(), [
            "api", "--method", "POST", "repos/owner/repo/git/refs",
            "-f", "ref=refs/tags/v1.0.1", "-f", f"sha={sha}",
        ])
        calls.unlink()
        self.git("tag", "-am", "Release", "v1.0.1")
        self.assertEqual(run().returncode, 0)  # Retrying the same tested commit is safe.
        self.assertFalse(calls.exists())
        self.git("commit", "--allow-empty", "-qm", "Another commit")
        env["GITHUB_SHA"] = self.git("rev-parse", "HEAD")
        self.assertNotEqual(run().returncode, 0)
        self.assertFalse(calls.exists())
