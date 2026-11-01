"""Exercise the two-release publication steps without contacting GitHub."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
VERSION = "1.2.3"
TAG = f"v{VERSION}"
PAYLOAD_TAG = f"payload-v{VERSION}"
INSTALLERS = {
    f"Heartbeat-{VERSION}-macos-arm64.dmg",
    f"Heartbeat-{VERSION}-macos-x64.dmg",
    f"Heartbeat-{VERSION}-windows-x64-setup.exe",
    f"Heartbeat-{VERSION}-windows-arm64-setup.exe",
    f"Heartbeat-{VERSION}-linux-x64.deb",
    f"Heartbeat-{VERSION}-android.apk",
    f"Heartbeat-{VERSION}-ios.ipa",
}
PAYLOAD = {"SHA256SUMS", "train-manifest.json", "bootstrap-linux-x64.json", "bundle-shell-o123.jar"}

FAKE_GH = r'''
import hashlib
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
state = Path(os.environ["FAKE_STATE"])
with Path(os.environ["FAKE_CALLS"]).open("a", encoding="utf-8") as log:
    log.write(json.dumps(args) + "\n")
if len(args) < 3 or args[0] != "release":
    raise SystemExit(2)
command, tag = args[1], args[2]
file = state / f"{tag}.json"


def save(data):
    file.write_text(json.dumps(data), encoding="utf-8")


def assets(arguments):
    names = []
    for value in arguments:
        if value.startswith("-"):
            break
        names.append(value)
    result = []
    for name in names:
        content = Path(name).read_bytes()
        digest = hashlib.sha256(content).hexdigest()
        if os.environ.get("FAKE_BAD_DIGEST") == Path(name).name:
            digest = None
        result.append({
            "name": Path(name).name,
            "size": len(content),
            "digest": f"sha256:{digest}" if digest else None,
        })
    return result


if command == "view":
    if not file.is_file():
        raise SystemExit(1)
    data = json.loads(file.read_text(encoding="utf-8"))
    fields = args[args.index("--json") + 1]
    if fields == "assets":
        print(json.dumps({"assets": data["assets"]}))
    elif fields == "isDraft,isPrerelease":
        print(f"{str(data['draft']).lower()}\t{str(data['prerelease']).lower()}")
    elif fields == "isDraft":
        print(str(data["draft"]).lower())
    else:
        raise SystemExit(2)
elif command in ("create", "upload"):
    if command == "create":
        data = {"draft": True, "prerelease": "--prerelease" in args, "assets": []}
    else:
        data = json.loads(file.read_text(encoding="utf-8"))
    data["assets"] = assets(args[3:])
    save(data)
elif command == "edit":
    data = json.loads(file.read_text(encoding="utf-8"))
    if "--draft=false" in args:
        data["draft"] = False
    save(data)
else:
    raise SystemExit(2)
'''


def workflow_step(name):
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = lines.index(f"      - name: {name}")
    start = lines.index("        run: |", start) + 1
    snippet = []
    for line in lines[start:]:
        if line and not line.startswith("          "):
            break
        snippet.append(line[10:])
    return "\n".join(snippet)


def bash_executable():
    if os.name != "nt":
        return shutil.which("bash")
    candidates = []
    git = shutil.which("git")
    if git:
        executable = Path(git).resolve()
        candidates.extend((executable.parent / "bash.exe", executable.parent.parent / "bin" / "bash.exe"))
    for variable in ("LOCALAPPDATA", "ProgramFiles"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]) / "Programs" / "Git" / "bin" / "bash.exe")
            candidates.append(Path(os.environ[variable]) / "Git" / "bin" / "bash.exe")
    return next((str(path) for path in candidates if path.is_file()), None)


BASH = bash_executable()


@unittest.skipUnless(BASH, "workflow publication uses bash")
class ReleasePublishTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "release").mkdir()
        for name in INSTALLERS | PAYLOAD:
            (self.root / "release" / name).write_text(name, encoding="utf-8")
        self.state = self.root / "state"
        self.state.mkdir()
        self.calls = self.root / "calls.jsonl"
        fake = self.root / "fake_gh.py"
        fake.write_text(FAKE_GH, encoding="utf-8")
        gh = self.root / "gh"
        gh.write_text('#!/bin/sh\n"$PYTHON" "$FAKE_GH" "$@"\n', encoding="utf-8")
        gh.chmod(0o755)
        self.env = dict(
            os.environ,
            PATH=f"{self.root}{os.pathsep}{os.environ['PATH']}",
            PYTHON=sys.executable,
            FAKE_GH=str(fake),
            FAKE_STATE=str(self.state),
            FAKE_CALLS=str(self.calls),
            GH_REPO="aequicor/Heartbeat",
            TAG=TAG,
            PAYLOAD_TAG=PAYLOAD_TAG,
            VERSION=VERSION,
        )

    def run_step(self, name):
        return subprocess.run(
            [BASH, "-euo", "pipefail", "-c", workflow_step(name)],
            cwd=self.root,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )

    def release(self, tag):
        return json.loads((self.state / f"{tag}.json").read_text(encoding="utf-8"))

    def invocations(self):
        if not self.calls.is_file():
            return []
        return [json.loads(line) for line in self.calls.read_text(encoding="utf-8").splitlines()]

    def test_one_step_publishes_payload_before_the_installer_only_latest_release(self):
        self.assertEqual(self.run_step("Separate installers from the payload").returncode, 0)
        result = self.run_step("Publish the releases")
        self.assertEqual(result.returncode, 0, result.stderr)

        main = self.release(TAG)
        payload = self.release(PAYLOAD_TAG)
        self.assertFalse(main["draft"])
        self.assertFalse(payload["draft"])
        self.assertEqual(INSTALLERS, {asset["name"] for asset in main["assets"]})
        self.assertEqual(PAYLOAD, {asset["name"] for asset in payload["assets"]})
        self.assertTrue(all(asset["digest"] for asset in main["assets"]))
        edits = [call[2] for call in self.invocations() if call[:2] == ["release", "edit"]]
        self.assertEqual([PAYLOAD_TAG, TAG], edits)
        notes = (self.root / "notes.md").read_text(encoding="utf-8")
        self.assertIn(f"releases/download/{PAYLOAD_TAG}/SHA256SUMS", notes)

    def test_missing_digest_stops_before_either_release_is_published(self):
        self.env["FAKE_BAD_DIGEST"] = sorted(INSTALLERS)[0]
        self.run_step("Separate installers from the payload")
        result = self.run_step("Publish the releases")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing GitHub SHA-256 digest", result.stderr)
        self.assertTrue(self.release(TAG)["draft"])
        self.assertTrue(self.release(PAYLOAD_TAG)["draft"])
        self.assertFalse([call for call in self.invocations() if call[:2] == ["release", "edit"]])

    def test_retry_accepts_published_payload_and_finishes_main_draft(self):
        self.run_step("Separate installers from the payload")
        original_payload = [
            {"name": name, "size": len(name.encode()),
             "digest": "sha256:" + hashlib.sha256(name.encode()).hexdigest()}
            for name in sorted(PAYLOAD)
        ]
        (self.state / f"{PAYLOAD_TAG}.json").write_text(
            json.dumps({"draft": False, "prerelease": False, "assets": original_payload}),
            encoding="utf-8",
        )
        (self.state / f"{TAG}.json").write_text(
            json.dumps({"draft": True, "prerelease": False, "assets": []}),
            encoding="utf-8",
        )

        result = self.run_step("Publish the releases")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(original_payload, self.release(PAYLOAD_TAG)["assets"])
        self.assertFalse(self.release(TAG)["draft"])
        self.assertFalse([
            call for call in self.invocations()
            if call[:3] == ["release", "upload", PAYLOAD_TAG]
        ])
        edits = [call[2] for call in self.invocations() if call[:2] == ["release", "edit"]]
        self.assertEqual([TAG], edits)

    def test_retry_rejects_changed_installer_checksums_before_touching_main(self):
        self.assertEqual(self.run_step("Separate installers from the payload").returncode, 0)
        original_payload = [
            {"name": name, "size": len(name.encode()),
             "digest": "sha256:" + hashlib.sha256(name.encode()).hexdigest()}
            for name in sorted(PAYLOAD)
        ]
        # A repeated upstream build can change installer bytes and their SHA256SUMS.
        installer = self.root / "installers" / sorted(INSTALLERS)[0]
        installer.write_bytes(b"rebuilt installer")
        (self.root / "release" / "SHA256SUMS").write_text(
            hashlib.sha256(installer.read_bytes()).hexdigest() + "  " + installer.name,
            encoding="utf-8",
        )
        (self.state / f"{PAYLOAD_TAG}.json").write_text(
            json.dumps({"draft": False, "prerelease": False, "assets": original_payload}),
            encoding="utf-8",
        )
        result = self.run_step("Publish the releases")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Published payload differs from this attempt: SHA256SUMS", result.stderr)
        self.assertEqual(original_payload, self.release(PAYLOAD_TAG)["assets"])
        self.assertFalse((self.state / f"{TAG}.json").exists())
        self.assertFalse([call for call in self.invocations() if call[1] != "view"])

    def test_retry_rejects_incomplete_payload_before_touching_main(self):
        self.run_step("Separate installers from the payload")
        (self.state / f"{PAYLOAD_TAG}.json").write_text(
            json.dumps({"draft": False, "prerelease": False, "assets": []}),
            encoding="utf-8",
        )
        result = self.run_step("Publish the releases")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Published payload asset set differs", result.stderr)
        self.assertFalse((self.state / f"{TAG}.json").exists())
        self.assertFalse([call for call in self.invocations() if call[1] != "view"])


if __name__ == "__main__":
    unittest.main()
