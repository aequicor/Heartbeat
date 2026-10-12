import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import publish_draft


class DraftReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        version = "152.0.6-hb1"
        digest = hashlib.sha256(b"fixture").hexdigest()
        api = {"file": f"jcef-api-{version}.jar", "sha256": digest}
        natives = {target: {"file": f"jcef-natives-{target}-{version}.jar", "sha256": digest}
                   for target in ("windows-amd64", "windows-arm64", "macosx-amd64", "macosx-arm64")}
        for artifact in [api, *natives.values()]:
            (self.directory / artifact["file"]).write_bytes(b"fixture")
        lock = {"artifactVersion": version, "apiArtifact": api, "nativeArtifacts": natives}
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
