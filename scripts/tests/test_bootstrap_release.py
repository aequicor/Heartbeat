"""Reject publication sets whose manifests no longer describe the uploaded archives."""
import hashlib
import base64
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_bootstrap_release", ROOT / "scripts/verify_bootstrap_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class BootstrapReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.private = self.root / "private.pem"
        self.keys = self.root / "trusted.txt"
        subprocess.run(["openssl", "genpkey", "-algorithm", "Ed25519", "-out", str(self.private)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        public = subprocess.check_output(["openssl", "pkey", "-in", str(self.private), "-pubout", "-outform", "DER"])
        self.keys.write_text(base64.b64encode(public).decode())
        for target in release.TARGETS:
            system, arch = target.split("-")
            body = dict(schema=1, version="1.2.3", channel="release", os=system, arch=arch)
            for component in ("runtime", "platform", "seed"):
                name = f"heartbeat-{component}-1.2.3-{target}.zip"
                path = self.root / name
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("manifest.json", '{"train":"otest"}')
                body[component] = dict(url=f"https://github.com/aequicor/Heartbeat/releases/download/v1.2.3/{name}",
                                       size=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
            body["seed"]["trainId"] = "otest"
            (self.root / f"bootstrap-{target}.json").write_text(json.dumps(body))
            subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", str(self.private), "-rawin",
                            "-in", str(self.root / f"bootstrap-{target}.json"),
                            "-out", str(self.root / f"bootstrap-{target}.json.sig")], check=True)

    def check(self):
        release.verify(self.root, "1.2.3", "otest", trusted_keys=self.keys)

    def change(self, change):
        path = self.root / "bootstrap-windows-arm64.json"
        body = json.loads(path.read_text())
        change(body)
        path.write_text(json.dumps(body))
        subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", str(self.private), "-rawin", "-in", str(path),
                        "-out", str(path.with_suffix(".json.sig"))], check=True)

    def test_complete_set(self):
        self.check()

    def test_corrupted_signature_of_the_correct_length_is_rejected(self):
        (self.root / "bootstrap-windows-arm64.json.sig").write_bytes(bytes(64))
        with self.assertRaisesRegex(ValueError, "signature does not match"):
            self.check()

    def test_tampered_manifest_is_rejected(self):
        path = self.root / "bootstrap-windows-arm64.json"
        path.write_text(path.read_text() + " ")
        with self.assertRaisesRegex(ValueError, "signature does not match"):
            self.check()

    def test_missing_platform(self):
        (self.root / "heartbeat-platform-1.2.3-windows-arm64.zip").unlink()
        with self.assertRaises(ValueError):
            self.check()

    def test_corrupt_archive(self):
        (self.root / "heartbeat-runtime-1.2.3-linux-x64.zip").write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            self.check()

    def test_wrong_target_or_url_or_train(self):
        for field, value in (("arch", "x64"), ("version", "9.9.9"), ("channel", "beta")):
            with self.subTest(field=field):
                self.change(lambda body: body.update({field: value}))
                with self.assertRaises(ValueError):
                    self.check()
                self.change(lambda body: body.update(arch="arm64", version="1.2.3", channel="release"))
        self.change(lambda body: body["seed"].update(trainId="different"))
        with self.assertRaises(ValueError):
            self.check()
        self.change(lambda body: body["seed"].update(trainId="otest", url="https://example.invalid/seed.zip"))
        with self.assertRaises(ValueError):
            self.check()

    def test_seed_contents_must_match_even_with_valid_digest(self):
        archive = self.root / "heartbeat-seed-1.2.3-windows-arm64.zip"
        with zipfile.ZipFile(archive, "w") as seed:
            seed.writestr("manifest.json", '{"train":"other"}')
        self.change(lambda body: body["seed"].update(size=archive.stat().st_size, sha256=release.digest(archive)))
        with self.assertRaises(ValueError):
            self.check()


class ReleaseCollectionTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("bash"), "workflow collection uses bash")
    def test_workflow_collects_the_matching_installer_and_five_bootstrap_assets(self):
        lines = (ROOT / ".github/workflows/release.yml").read_text().splitlines()
        for target in release.TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                step = "Name the Linux installer" if target == "linux-x64" else "Name the installer"
                start = lines.index("      - name: " + step)
                start = lines.index("        run: |", start) + 1
                command = []
                for line in lines[start:]:
                    if line and not line.startswith("          "):
                        break
                    command.append(line[10:])
                extension = "-setup.exe" if target.startswith("windows") else ".deb" if target == "linux-x64" else ".dmg"
                installer = f"Heartbeat-1.2.3-{target}{extension}"
                names = [f"bootstrap-{target}.json", f"bootstrap-{target}.json.sig"] + [
                    f"heartbeat-{part}-1.2.3-{target}.zip" for part in ("runtime", "platform", "seed")]
                for folder, entries in (("native-launcher/build/thin/installers", [installer]),
                                        ("desktop/build/bootstrap", names),
                                        ("desktop/build/release-components", ["Heartbeat-1.2.3-chromium-extension.zip"])):
                    directory = root / "platform-main" / folder
                    directory.mkdir(parents=True)
                    for name in entries:
                        (directory / name).write_text(name)
                env = dict(os.environ, VERSION="1.2.3", PLATFORM=target,
                           RUNNER_OS="Windows" if target.startswith("windows") else "Linux")
                subprocess.run(["bash", "-euo", "pipefail", "-c", "\n".join(command)], cwd=root, env=env, check=True)
                expected = set(names + [installer])
                if target == "linux-x64":
                    expected.add("Heartbeat-1.2.3-chromium-extension.zip")
                self.assertEqual(expected, {path.name for path in (root / "release").iterdir()})
