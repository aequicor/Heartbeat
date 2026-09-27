"""Exercise review budgets against real temporary Git histories and indexes."""

from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import tiktoken

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_commit_size as policy


class CommitSizeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.encodings = [tiktoken.get_encoding(name) for name in policy.ENCODINGS]

    def setUp(self):
        self.environment = patch.dict(os.environ, {
            "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
            "GITHUB_ACTIONS": "false", "GITHUB_STEP_SUMMARY": "",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.directory = tempfile.TemporaryDirectory(prefix="heartbeat-commit-policy-")
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Commit Policy Test")
        self.git("config", "user.email", "commit-policy@example.invalid")
        self.git("config", "core.autocrlf", "false")

    def git(self, *args):
        return policy.git(*args, cwd=self.repo).decode().strip()

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", "Test change")
        return self.git("rev-parse", "HEAD")

    def measure(self, commit=None):
        text, stats = policy.read_diff(commit, cwd=self.repo)
        return policy.measure(commit or "staged", text, stats, self.encodings)

    def test_budget_boundaries_and_exit_code(self):
        for tokens, files, status, exit_code in [
            (20_000, 20, "PASS", 0), (20_001, 20, "WARN", 0),
            (25_000, 20, "WARN", 0), (25_001, 1, "FAIL", 1),
            (100, 21, "WARN", 0),
        ]:
            with self.subTest(tokens=tokens, files=files), redirect_stdout(io.StringIO()):
                result = policy.Measurement("test", tokens, files, 0, 0)
                self.assertEqual(status, result.status)
                self.assertEqual(exit_code, policy.report([result]))

    def test_root_commit_counts_docs_tests_and_lock_files(self):
        self.write("README.md", "Описание\n")
        self.write("src/test/Test.kt", "fun test() = Unit\n")
        self.write("gradle.lockfile", "dependency:1.0\n")
        result = self.measure(self.commit())
        self.assertEqual((3, 3, 0), (result.files, result.lines, result.binaries))
        self.assertGreater(result.tokens, 0)

    def test_staged_index_is_independent_of_working_tree(self):
        self.write("code.kt", "val value = 1\n")
        self.git("add", "code.kt")
        staged = self.measure()
        self.write("code.kt", 'val value = "huge"\n' * 6000)
        self.write("untracked.kt", "not in the commit\n")
        self.assertEqual(staged, self.measure())

    def test_empty_staging_has_zero_cost(self):
        result = self.measure()
        self.assertEqual((0, 0, 0), (result.tokens, result.files, result.lines))

    def test_rename_with_spaces_and_unicode_is_one_file(self):
        self.write("old name.kt", "val x = 1\n")
        self.commit()
        self.git("mv", "old name.kt", "новое имя.kt")
        result = self.measure()
        self.assertEqual((1, 0, 0), (result.files, result.lines, result.binaries))
        self.assertGreater(result.tokens, 0)

    def test_binary_and_deleted_file_are_not_silently_dropped(self):
        self.write("old.kt", "val a = 1\nval b = 2\n")
        self.commit()
        (self.repo / "old.kt").unlink()
        self.write("image.bin", b"\0\xff\x00binary")
        result = self.measure(self.commit())
        self.assertEqual((2, 2, 1), (result.files, result.lines, result.binaries))

    def test_reverting_large_commit_does_not_hide_it_from_range(self):
        self.write("base.kt", "val base = 1\n")
        base = self.commit()
        self.write("large.kt", 'val value = "large"\n' * 6000)
        large = self.commit()
        (self.repo / "large.kt").unlink()
        reverted = self.commit()
        self.assertEqual("", self.git("diff", base, reverted))
        selected = policy.select_commits(base, reverted, cwd=self.repo)
        self.assertEqual([large, reverted], selected)
        self.assertEqual(["FAIL", "FAIL"], [self.measure(sha).status for sha in selected])

    def test_base_branch_history_is_excluded(self):
        self.write("base.kt", "base\n")
        base = self.commit()
        self.git("checkout", "-b", "feature")
        self.write("feature.kt", "feature\n")
        feature = self.commit()
        self.git("checkout", "main")
        self.write("upstream.kt", "upstream\n")
        upstream = self.commit()
        self.assertEqual([feature], policy.select_commits(upstream, feature, cwd=self.repo))
        self.assertEqual([], policy.select_commits(base, base, cwd=self.repo))

    def test_merge_changes_are_measured_against_first_parent(self):
        self.write("base.kt", "base\n")
        self.commit()
        self.git("checkout", "-b", "feature")
        self.write("feature.kt", "feature\n")
        self.commit()
        self.git("checkout", "main")
        self.write("main.kt", "main\n")
        self.commit()
        self.git("merge", "--no-ff", "--no-edit", "feature")
        merged = self.git("rev-parse", "HEAD")
        text, _ = policy.read_diff(merged, cwd=self.repo)
        self.assertIn("+feature", text)
        self.assertNotIn("+main", text)
        self.assertEqual((1, 1), (self.measure(merged).files, self.measure(merged).lines))

    def sync_base_into_feature(self, extra=None):
        """Feature merges a large upstream change with a conflict; returns (base, merge)."""
        self.write("shared.kt", "val shared = 0\n")
        self.commit()
        self.git("checkout", "-b", "feature")
        self.write("shared.kt", "val shared = 1\n")
        self.commit()
        self.git("checkout", "main")
        self.write("upstream.kt", 'val value = "upstream"\n' * 6000)
        self.write("shared.kt", "val shared = 2\n")
        base = self.commit()
        self.git("checkout", "feature")
        with self.assertRaises(RuntimeError):
            self.git("merge", "--no-edit", "main")
        self.write("shared.kt", "val shared = 3\n")
        if extra:
            self.write(*extra)
        self.git("add", "--all")
        self.git("commit", "--quiet", "--no-edit")
        return base, self.git("rev-parse", "HEAD")

    def test_base_sync_merge_counts_only_conflict_resolution(self):
        base, merge = self.sync_base_into_feature()
        self.assertIn(merge, policy.select_commits(base, merge, cwd=self.repo))
        self.assertTrue(policy.is_base_sync(merge, base, cwd=self.repo))
        text, stats = policy.read_diff(merge, cwd=self.repo, base_sync=True)
        result = policy.measure(merge, text, stats, self.encodings)
        self.assertIn("+val shared = 3", text)
        self.assertNotIn("upstream", text)
        self.assertEqual("PASS", result.status)
        self.assertEqual("FAIL", self.measure(merge).status)

    def test_base_sync_merge_still_counts_extra_changes(self):
        base, merge = self.sync_base_into_feature(extra=("hidden.kt", 'val hidden = "evil"\n' * 6000))
        text, stats = policy.read_diff(merge, cwd=self.repo, base_sync=True)
        self.assertEqual("FAIL", policy.measure(merge, text, stats, self.encodings).status)

    def test_octopus_base_sync_uses_first_parent_and_enforces_budget(self):
        self.write("base.kt", "base\n")
        root = self.commit()
        self.git("checkout", "-b", "feature")
        self.write("feature.kt", "feature\n")
        self.commit()
        for branch in ("side-one", "side-two"):
            self.git("checkout", "-b", branch, root)
            self.write(f"{branch}.kt", f"{branch}\n")
            self.commit()
        self.git("checkout", "main")
        self.git("merge", "--no-ff", "--no-edit", "side-one", "side-two")
        base = self.git("rev-parse", "HEAD")
        self.git("checkout", "feature")
        self.git("merge", "--no-ff", "--no-edit", "side-one", "side-two")
        merge = self.git("rev-parse", "HEAD")
        parents = self.git("rev-list", "--parents", "--max-count=1", merge).split()[1:]
        self.assertEqual(3, len(parents))
        self.assertTrue(all(policy.is_ancestor(parent, base, cwd=self.repo) for parent in parents[1:]))

        command = [sys.executable, str(Path(policy.__file__).resolve()), "--base", base, "--head", "HEAD"]
        clean = subprocess.run(command, cwd=self.repo, capture_output=True, text=True)
        self.assertEqual(0, clean.returncode, clean.stdout + clean.stderr)
        self.assertNotIn("(base sync)", clean.stdout)
        self.assertFalse(policy.is_base_sync(merge, base, cwd=self.repo))

        self.write("hidden.kt", 'val hidden = "evil"\n' * 6000)
        self.git("add", "hidden.kt")
        self.git("commit", "--amend", "--no-edit", "--quiet")
        oversized = subprocess.run(command, cwd=self.repo, capture_output=True, text=True)
        self.assertEqual(1, oversized.returncode, oversized.stdout + oversized.stderr)
        self.assertIn("FAIL", oversized.stdout)
        self.assertNotIn("(base sync)", oversized.stdout)

    def test_merging_unreviewed_branch_is_not_a_base_sync(self):
        self.write("base.kt", "base\n")
        base = self.commit()
        self.git("checkout", "-b", "side")
        self.write("side.kt", "side\n")
        self.commit()
        self.git("checkout", "main")
        self.write("main.kt", "main\n")
        self.commit()
        self.git("merge", "--no-ff", "--no-edit", "side")
        self.assertFalse(policy.is_base_sync(self.git("rev-parse", "HEAD"), base, cwd=self.repo))
        self.assertFalse(policy.is_base_sync(base, base, cwd=self.repo))

    def test_diff_configuration_does_not_change_measurement(self):
        self.write("code.kt", "".join(f"val x{i} = {i}\n" for i in range(30)))
        self.commit()
        self.write("code.kt", "".join(f"val x{i} = {i if i != 15 else 99}\n" for i in range(30)))
        self.git("add", "code.kt")
        before = policy.read_diff(cwd=self.repo)
        for name, value in {
            "diff.context": "100", "diff.noprefix": "true", "diff.mnemonicPrefix": "true",
            "diff.algorithm": "histogram", "diff.renames": "false", "core.abbrev": "12",
            "color.ui": "always", "diff.external": "nonexistent-diff-command",
        }.items():
            self.git("config", name, value)
        self.assertEqual(before, policy.read_diff(cwd=self.repo))

    def test_special_token_literals_are_treated_as_source_text(self):
        self.write("literal.kt", 'val example = "<|endoftext|>"\n')
        result = self.measure(self.commit())
        self.assertGreater(result.tokens, 0)

    def test_invalid_ref_fails_instead_of_passing_empty_range(self):
        with self.assertRaises(RuntimeError):
            policy.select_commits("missing", "HEAD", cwd=self.repo)

    def test_shallow_history_fails_closed(self):
        self.write("base.kt", "base\n")
        self.commit()
        self.write("second.kt", "second\n")
        head = self.commit()
        with tempfile.TemporaryDirectory(prefix="heartbeat-shallow-") as shallow:
            subprocess.run(
                ["git", "clone", "--quiet", "--depth=1", self.repo.as_uri(), shallow],
                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            with self.assertRaisesRegex(RuntimeError, "Full Git history"):
                policy.select_commits(head, head, cwd=shallow)


if __name__ == "__main__":
    unittest.main()
