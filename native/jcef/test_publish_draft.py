import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build_runtime
import publish_draft


class DraftReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        version = json.loads((build_runtime.HERE / "source-lock.json").read_text())["artifactVersion"]
        digest = hashlib.sha256(b"fixture").hexdigest()
        api = {"file": f"jcef-api-{version}.jar", "sha256": digest}
        natives = {target: {"file": f"jcef-natives-{target}-{version}.jar", "sha256": digest}
                   for target in ("windows-amd64", "windows-arm64", "macosx-amd64", "macosx-arm64")}
        for artifact in [api, *natives.values()]:
            (self.directory / artifact["file"]).write_bytes(b"fixture")
        lock = {"artifactVersion": version, "apiArtifact": api, "nativeArtifacts": natives,
                "cefDistributions": {target: {} for target in natives}}
        (self.directory / "source-lock.json").write_text(json.dumps(lock))
        (self.directory / "SHA256SUMS").write_text("fixture sums")
        (self.directory / "runtime-manifest.json").write_text("[]")
        self.arguments = ["publish_draft.py", "--artifacts", str(self.directory),
                          "--repository", "owner/repository", "--commit", "a" * 40]

    def test_new_release_is_draft_prerelease_with_exact_commit_and_all_assets(self):
        responses = [subprocess.CompletedProcess([], 1, "", "release not found"),
                     subprocess.CompletedProcess([], 0, "https://example/release", "")]
        with patch("sys.argv", self.arguments), patch.object(publish_draft, "gh", side_effect=responses) as gh:
            publish_draft.main()
        arguments = gh.call_args.args
        self.assertIn("--draft", arguments)
        self.assertIn("--prerelease", arguments)
        self.assertIn("--latest=false", arguments)
        self.assertIn("a" * 40, arguments)
        self.assertEqual(5, sum(str(value).endswith(".jar") for value in arguments))
        self.assertNotIn("--clobber", arguments)

    def test_corrupt_local_jar_is_rejected_before_any_github_action(self):
        next(self.directory.glob("*.jar")).write_bytes(b"corrupt")
        with patch("sys.argv", self.arguments), patch.object(publish_draft, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "does not match"):
                publish_draft.main()
        gh.assert_not_called()

    def test_linux_release_requires_its_native_jar_and_publishes_six_jars(self):
        path = self.directory / "source-lock.json"
        lock = json.loads(path.read_text())
        lock["cefDistributions"]["linux-amd64"] = {}
        path.write_text(json.dumps(lock))
        with patch("sys.argv", self.arguments), patch.object(publish_draft, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "native targets"):
                publish_draft.main()
        gh.assert_not_called()
        name = f"jcef-natives-linux-amd64-{lock['artifactVersion']}.jar"
        (self.directory / name).write_bytes(b"fixture")
        lock["nativeArtifacts"]["linux-amd64"] = {
            "file": name, "sha256": hashlib.sha256(b"fixture").hexdigest(),
        }
        path.write_text(json.dumps(lock))
        responses = [subprocess.CompletedProcess([], 1, "", "release not found"),
                     subprocess.CompletedProcess([], 0, "linux release", "")]
        with patch("sys.argv", self.arguments), patch.object(publish_draft, "gh", side_effect=responses) as gh:
            publish_draft.main()
        self.assertEqual(6, sum(str(value).endswith(".jar") for value in gh.call_args.args))

    def test_candidate_version_must_be_explicitly_selected(self):
        with patch("sys.argv", self.arguments + ["--version", "152.0.6-hb99"]), \
                patch.object(publish_draft, "gh") as gh:
            with self.assertRaisesRegex(ValueError, "Unexpected release version"):
                publish_draft.main()
        gh.assert_not_called()

    def test_explicit_candidate_creates_new_tag_without_overwriting_old_release(self):
        path = self.directory / "source-lock.json"
        lock = json.loads(path.read_text())
        old_version = lock["artifactVersion"]
        candidate_version = "152.0.6-hb99"
        lock["artifactVersion"] = candidate_version
        for artifact in [lock["apiArtifact"], *lock["nativeArtifacts"].values()]:
            renamed = artifact["file"].replace(old_version, candidate_version)
            (self.directory / artifact["file"]).rename(self.directory / renamed)
            artifact["file"] = renamed
        path.write_text(json.dumps(lock))
        responses = [subprocess.CompletedProcess([], 1, "", "release not found"),
                     subprocess.CompletedProcess([], 0, "candidate release", "")]
        with patch("sys.argv", self.arguments + ["--version", candidate_version]), \
                patch.object(publish_draft, "gh", side_effect=responses) as gh:
            publish_draft.main()
        self.assertEqual(candidate_version, gh.call_args.args[2])
        self.assertNotIn("--clobber", gh.call_args.args)

    def test_existing_release_with_different_checksums_is_never_overwritten(self):
        def response(*arguments, **kwargs):
            if arguments[1] == "view":
                assets = [{"name": name} for name in ("SHA256SUMS", "source-lock.json", "runtime-manifest.json")]
                return subprocess.CompletedProcess([], 0, json.dumps({"assets": assets, "url": "existing"}), "")
            self.assertEqual("download", arguments[1])
            destination = Path(arguments[arguments.index("--dir") + 1])
            (destination / "SHA256SUMS").write_text("different build")
            return subprocess.CompletedProcess([], 0, "", "")
        with patch("sys.argv", self.arguments), patch.object(publish_draft, "gh", side_effect=response) as gh:
            with self.assertRaisesRegex(ValueError, "Immutable release differs"):
                publish_draft.main()
        self.assertEqual(2, gh.call_count)


if __name__ == "__main__":
    unittest.main()
