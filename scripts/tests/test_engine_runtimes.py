"""Immutable runtime delivery, native provenance and extensible publication planning."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "engine_runtimes.py"
spec = importlib.util.spec_from_file_location("engine_runtimes", SCRIPT)
runtimes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtimes)


class RuntimeDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text(
            '[versions]\nkimi-runtime="1.0-hb1"\nnode="22.0.0"\n', encoding="utf-8")
        source = self.root / "native/kimi"
        source.mkdir(parents=True)
        (source / "source-lock.json").write_text(json.dumps({"commit": "a" * 40, "permissionGate": 1}), encoding="utf-8")
        (source / "build.py").write_text("pinned builder", encoding="utf-8")
        self.recipe = {"id": "kimi", "versionKey": "kimi-runtime", "nodeVersionKey": "node",
                       "toolVersionKeys": ["node"], "sourceLock": "native/kimi/source-lock.json",
                       "inputs": ["native/kimi"], "build": ["{python}", "native/kimi/build.py"],
                       "test": ["{python}", "-m", "unittest"], "output": "native/kimi/out/{target}",
                       "platforms": list(runtimes.HOSTS)}
        self.catalog(self.recipe)
        self.entry = runtimes.recipes(self.root)[0]

    def tearDown(self):
        self.temp.cleanup()

    def catalog(self, *entries):
        runtimes.write_json(self.root / "native/runtimes.json", {"schemaVersion": 1, "engines": entries})

    def output(self, target):
        source = self.root / "native/kimi/out" / target
        source.mkdir(parents=True, exist_ok=True)
        binary = source / ("kimi.exe" if target.startswith("win32-") else "kimi")
        binary.write_bytes(b"native host controlled fixture")
        (source / "LICENSE").write_text("fixture license", encoding="utf-8")
        (source / "runtime.properties").write_text(
            f"version={self.entry['version']}\nsourceCommit={self.entry['sourceCommit']}\npermissionGate=1\n"
            f"target={target}\nsha256={hashlib.sha256(binary.read_bytes()).hexdigest()}\n", encoding="utf-8")
        return source

    def packages(self):
        folder = self.root / "release"
        for target in self.entry["platforms"]:
            self.output(target)
            runtimes.package(self.entry, target, folder / target, self.root)
        return folder

    def published_release(self):
        folder = self.packages()
        files = runtimes.aggregate(self.entry, folder)
        manifest = json.loads(files[0].read_text(encoding="utf-8"))
        assets = [{"name": file.name, "size": file.stat().st_size} for file in files]
        return {"draft": False, "assets": assets}, manifest

    def test_native_host_architecture_is_normalized_for_all_five_runners(self):
        for system, arch, target in [("Windows", "ARM64", "win32-arm64"), ("Windows", "AMD64", "win32-x64"),
                                     ("Darwin", "arm64", "darwin-arm64"), ("Darwin", "x86_64", "darwin-x64"),
                                     ("Linux", "x86_64", "linux-x64")]:
            with patch.object(runtimes.platform, "system", return_value=system), \
                    patch.object(runtimes.platform, "machine", return_value=arch):
                self.assertEqual(target, runtimes.host_target())

    def test_missing_runtime_plans_all_five_native_hosts(self):
        with patch.object(runtimes, "release", return_value=None):
            plan = runtimes.make_plan([self.entry], "owner/repo")
        self.assertEqual(["kimi"], plan["engines"])
        self.assertEqual(set(runtimes.HOSTS), {item["target"] for item in plan["matrix"]["include"]})
        self.assertEqual(5, len(plan["matrix"]["include"]))

    def test_another_engine_is_added_through_catalog_without_workflow_changes(self):
        other = dict(self.recipe, id="other")
        self.catalog(self.recipe, other)
        entries = runtimes.recipes(self.root)
        with patch.object(runtimes, "release", return_value=None):
            plan = runtimes.make_plan(entries, "owner/repo")
        self.assertEqual(["kimi", "other"], plan["engines"])
        self.assertEqual(10, len(plan["matrix"]["include"]))

    def test_published_immutable_version_is_reused_without_build(self):
        existing, manifest = self.published_release()
        with patch.object(runtimes, "release", return_value=existing), patch.object(runtimes, "gh") as gh:
            gh.return_value.stdout = json.dumps(manifest)
            plan = runtimes.make_plan([self.entry], "owner/repo")
        self.assertEqual([], plan["engines"])
        self.assertEqual([], plan["matrix"]["include"])

    def test_changed_build_inputs_require_new_version_instead_of_overwriting_release(self):
        existing, manifest = self.published_release()
        (self.root / "native/kimi/build.py").write_text("changed builder", encoding="utf-8")
        changed = runtimes.recipes(self.root)[0]
        with patch.object(runtimes, "gh") as gh:
            gh.return_value.stdout = json.dumps(manifest)
            with self.assertRaisesRegex(ValueError, "bump the runtime version"):
                runtimes.published(changed, "owner/repo", existing)

    def test_generated_outputs_and_unit_tests_do_not_change_build_identity(self):
        before = self.entry["buildFingerprint"]
        self.packages()
        (self.root / "native/kimi/test_build.py").write_text("a test", encoding="utf-8")
        self.assertEqual(before, runtimes.recipes(self.root)[0]["buildFingerprint"])

    def test_package_has_flat_safe_files_and_reproducible_hash(self):
        target = "win32-x64"
        self.output(target)
        archive = runtimes.package(self.entry, target, self.root / "one", self.root)
        other = runtimes.package(self.entry, target, self.root / "two", self.root)
        self.assertEqual(archive.read_bytes(), other.read_bytes())
        with zipfile.ZipFile(archive) as zipped:
            self.assertEqual(["kimi.exe", "runtime.properties", "LICENSE"], zipped.namelist())
            self.assertEqual(0o100755, zipped.getinfo("kimi.exe").external_attr >> 16)
        part = runtimes.read_json(archive.parent / "part.json")
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), part["platforms"][target]["sha256"])

    def test_packaging_rejects_wrong_target_and_modified_executable_before_publication(self):
        source = self.output("win32-x64")
        properties = source / "runtime.properties"
        original = properties.read_text(encoding="utf-8")
        properties.write_text(original.replace("win32-x64", "win32-arm64"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "adapter pin"):
            runtimes.package(self.entry, "win32-x64", self.root / "release", self.root)
        properties.write_text(original, encoding="utf-8")
        (source / "kimi.exe").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "adapter pin"):
            runtimes.package(self.entry, "win32-x64", self.root / "release", self.root)

    def test_publication_requires_matching_artifacts_of_every_platform(self):
        folder = self.packages()
        runtimes.aggregate(self.entry, folder)
        (folder / "win32-arm64/part.json").unlink()
        with self.assertRaises(FileNotFoundError):
            runtimes.aggregate(self.entry, folder)

    def test_publication_rejects_cross_version_or_corrupt_artifacts(self):
        folder = self.packages()
        file = folder / "linux-x64/part.json"
        part = runtimes.read_json(file)
        part["sourceCommit"] = "0" * 40
        runtimes.write_json(file, part)
        with self.assertRaisesRegex(ValueError, "provenance"):
            runtimes.aggregate(self.entry, folder)
        self.packages()
        archive = folder / "linux-x64" / runtimes.archive_name(self.entry, "linux-x64")
        archive.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            runtimes.aggregate(self.entry, folder)

    def test_bad_catalog_identity_platform_and_external_path_are_rejected(self):
        for recipe in [dict(self.recipe, id="../escape"), dict(self.recipe, platforms=["linux-arm64"]),
                       dict(self.recipe, inputs=["../external"]), dict(self.recipe, platforms=["win32-x64"] * 2)]:
            self.catalog(recipe)
            with self.assertRaises(ValueError):
                runtimes.recipes(self.root)

    def test_release_lookup_distinguishes_not_found_from_network_and_permission_failures(self):
        with patch.object(runtimes, "gh", return_value=subprocess.CompletedProcess([], 1, "", "HTTP 404: Not Found")):
            self.assertIsNone(runtimes.release(self.entry, "owner/repo"))
        with patch.object(runtimes, "gh", return_value=subprocess.CompletedProcess([], 1, "", "HTTP 403: Forbidden")):
            with self.assertRaises(RuntimeError):
                runtimes.release(self.entry, "owner/repo")

    def test_published_release_is_never_uploaded_or_edited_again(self):
        existing, manifest = self.published_release()
        with patch.object(runtimes, "release", return_value=existing), patch.object(runtimes, "gh") as gh:
            gh.return_value.stdout = json.dumps(manifest)
            runtimes.publish(self.entry, "owner/repo", self.root / "not-needed", "a" * 40)
            self.assertEqual(1, gh.call_count)
            self.assertEqual(("release", "download"), gh.call_args.args[:2])

    def test_new_release_stays_draft_until_every_asset_is_uploaded(self):
        folder = self.packages()
        with patch.object(runtimes, "release", return_value=None), patch.object(runtimes, "gh") as gh:
            runtimes.publish(self.entry, "owner/repo", folder, "a" * 40)
            commands = [call.args for call in gh.call_args_list]
        self.assertEqual(["create", "upload", "edit"], [command[1] for command in commands])
        self.assertIn("--draft", commands[0])
        self.assertIn("--draft=false", commands[2])
        self.assertIn("--latest=false", commands[2])


class RepositoryPinTest(unittest.TestCase):
    def test_kimi_adapter_and_catalog_pin_the_same_runtime_and_source(self):
        entry = next(entry for entry in runtimes.recipes() if entry["id"] == "kimi")
        file = runtimes.ROOT / ("features/ai-engine/kimi/impl/src/jvmMain/kotlin/io/aequicor/heartbeat/"
                               "feature/aiengine/kimi/impl/data/KimiRuntimeRelease.kt")
        adapter = file.read_text(encoding="utf-8")
        self.assertIn(f'KIMI_RUNTIME_VERSION = "{entry["version"]}"', adapter)
        self.assertIn(f'KIMI_SOURCE_COMMIT = "{entry["sourceCommit"]}"', adapter)
        self.assertIn(f'KIMI_PERMISSION_GATE = {entry["permissionGate"]}', adapter)


if __name__ == "__main__":
    unittest.main()
