"""Check the shared pre-release counter and that betas never move the stable version."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "prerelease_title.py"
spec = importlib.util.spec_from_file_location("prerelease_title", SCRIPT)
prerelease_title = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prerelease_title)


def release(tag, published, prerelease=True, draft=False):
    return {"tagName": tag, "isDraft": draft, "isPrerelease": prerelease, "publishedAt": published}


TRAIN = "train-t0123456789ab"


class PlanTests(unittest.TestCase):
    def test_trains_and_betas_share_one_counter_after_the_latest_stable(self):
        releases = [
            release("beta-1.0.9.2", "2026-03-04"),
            release(TRAIN, "2026-03-03"),
            release("train-t0123456789ac", "2026-03-02"),
            release("v1.0.8", "2026-03-01", prerelease=False),
            release("train-t0123456789ad", "2026-02-01"),
            release("v1.0.7", "2026-01-01", prerelease=False),
        ]
        result = prerelease_title.plan(releases, "1.0.0")
        self.assertEqual(result["stable"], "v1.0.8")
        self.assertEqual(result["version"], "1.0.9")
        self.assertEqual(result["counter"], 4)
        self.assertEqual(result["title"], "Heartbeat 1.0.9.4")
        self.assertEqual(result["tag"], "beta-1.0.9.4")

    def test_drafts_prereleases_and_foreign_tags_do_not_move_the_version_or_count(self):
        releases = [
            release("v2.0.0", "2026-03-05", draft=True, prerelease=False),
            release("v3.0.0-rc1", "2026-03-04"),
            release("jcef-100", "2026-03-04"),
            release(TRAIN, "2026-03-03", draft=True),
            release("v1.2.3", "2026-03-01", prerelease=False),
        ]
        result = prerelease_title.plan(releases, "1.0.0")
        self.assertEqual((result["version"], result["counter"]), ("1.2.4", 1))

    def test_highest_stable_version_wins_over_publication_order(self):
        releases = [release("v1.9.0", "2026-01-01", prerelease=False), release("v1.10.0", "2025-01-01", prerelease=False)]
        self.assertEqual(prerelease_title.plan(releases, "1.0.0")["version"], "1.10.1")

    def test_without_stable_release_the_baseline_is_bumped_and_all_prereleases_count(self):
        result = prerelease_title.plan([release(TRAIN, "2026-01-01")], "1.0.0")
        self.assertEqual((result["stable"], result["version"], result["label"]), ("", "1.0.1", "1.0.1.2"))


class CliTests(unittest.TestCase):
    def run_cli(self, releases, *args):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "gradle.properties").write_text("heartbeat.version=1.0.0\n")
            return subprocess.run(
                [sys.executable, str(SCRIPT), *args], cwd=directory, input=json.dumps(releases),
                capture_output=True, text=True, check=False,
            )

    def test_cli_prints_key_value_lines(self):
        result = self.run_cli([release("v1.0.8", "2026-03-01", prerelease=False)])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("title=Heartbeat 1.0.9.1\n", result.stdout)
        self.assertIn("tag=beta-1.0.9.1\n", result.stdout)

    def test_only_master_publishes_but_dry_run_may_use_any_ref(self):
        for ref in ("refs/heads/work", "refs/tags/v1.0.0", "master"):
            with self.subTest(ref=ref):
                self.assertNotEqual(self.run_cli([], "--ref", ref, "--dry-run", "false").returncode, 0)
                self.assertEqual(self.run_cli([], "--ref", ref, "--dry-run", "true").returncode, 0)
        self.assertEqual(self.run_cli([], "--ref", "refs/heads/master", "--dry-run", "false").returncode, 0)

    def test_malformed_input_fails_without_output(self):
        result = self.run_cli([{"tagName": "v1.0.0"}])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
