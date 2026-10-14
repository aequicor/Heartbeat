"""Packaging/verification contracts without downloading CEF or requiring a C++ SDK."""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_runtime as runtime


class RuntimePackagingTest(unittest.TestCase):
    def setUp(self):
        runtime.select_inputs(runtime.HERE / "source-lock.json", runtime.HERE / "request-context-settings.patch")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative, data=b"fixture"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def test_cli_defaults_select_the_canonical_lock_before_packaging(self):
        canonical_version = runtime.lock()["artifactVersion"]
        with patch.object(runtime, "VERSION", "unselected-bootstrap"), \
                patch.object(runtime, "package") as package, \
                patch("sys.argv", ["build_runtime.py", "package", "--target", "windows-amd64"]):
            package.side_effect = lambda *args: self.assertEqual(canonical_version, runtime.VERSION)
            runtime.main()
            package.assert_called_once()

    def test_release_identity_is_derived_from_the_lock(self):
        data = runtime.lock()
        version, tag = runtime.release_identity(data)
        self.assertEqual(data["artifactVersion"], version)
        self.assertEqual(f"jcef-{data['jcefCommit'][:7]}+cef-{data['cefVersion']}+heartbeat-"
                         f"{version.rsplit('-', 1)[1]}", tag)
        self.assertEqual((runtime.VERSION, runtime.RELEASE_TAG), (version, tag))
        with self.assertRaisesRegex(ValueError, "Artifact version"):
            runtime.release_identity(dict(data, artifactVersion="152.0.6-hb0"))

    def test_lock_pins_the_cef_distribution_of_every_target(self):
        data = runtime.lock()
        for target in runtime.release_targets(data):
            cef_platform, _ = runtime.TARGETS[target]
            name, _ = runtime.cef_distribution(data, target)
            self.assertEqual(f"cef_binary_{data['cefVersion']}_{cef_platform}.tar.bz2", name)
        with self.assertRaisesRegex(ValueError, "does not pin"):
            runtime.cef_distribution(dict(data, cefDistributions={}), "windows-amd64")
        unpinned = {"windows-amd64": dict(data["cefDistributions"]["windows-amd64"], sha1="upstream")}
        with self.assertRaisesRegex(ValueError, "Invalid CEF distribution checksum"):
            runtime.cef_distribution(dict(data, cefDistributions=unpinned), "windows-amd64")

    def test_cef_distribution_matches_the_locked_digest_before_cmake_extracts_it(self):
        payload = b"cef distribution"
        name = f"cef_binary_{runtime.lock()['cefVersion']}_macosarm64.tar.bz2"
        locked = dict(runtime.lock(), cefDistributions={
            "macosx-arm64": {"file": name, "sha1": hashlib.sha1(payload).hexdigest()}})
        archive = self.root / "third_party/cef" / name
        with patch.object(runtime, "lock", return_value=locked):
            with patch.object(runtime.urllib.request, "urlopen", return_value=io.BytesIO(b"tampered")) as urlopen:
                with self.assertRaisesRegex(ValueError, "differs from the source lock"):
                    runtime.prepare_cef_distribution(self.root, "macosx-arm64")
            self.assertTrue(urlopen.call_args.args[0].startswith(runtime.CEF_DOWNLOADS))
            self.assertIn("%2B", urlopen.call_args.args[0])
            self.assertEqual([], list(archive.parent.iterdir()))
            with patch.object(runtime.urllib.request, "urlopen", return_value=io.BytesIO(payload)):
                runtime.prepare_cef_distribution(self.root, "macosx-arm64")
            self.assertEqual(payload, archive.read_bytes())
            with patch.object(runtime.urllib.request, "urlopen") as urlopen:
                runtime.prepare_cef_distribution(self.root, "macosx-arm64")
                urlopen.assert_not_called()
            archive.write_bytes(b"replaced in the cache")
            with self.assertRaisesRegex(ValueError, "differs from the source lock"):
                runtime.prepare_cef_distribution(self.root, "macosx-arm64")
            archive.unlink()
            # A tree left without its archive (or cut short mid-extraction) is never handed to CMake.
            stale = self.write(f"third_party/cef/{name.removesuffix('.tar.bz2')}/partial")
            with patch.object(runtime.urllib.request, "urlopen", return_value=io.BytesIO(payload)):
                runtime.prepare_cef_distribution(self.root, "macosx-arm64")
            self.assertFalse(stale.parent.exists())
            self.assertEqual(payload, archive.read_bytes())
            stale = self.write(f"third_party/cef/{name.removesuffix('.tar.bz2')}/partial")
            with patch.object(runtime.urllib.request, "urlopen") as urlopen:
                runtime.prepare_cef_distribution(self.root, "macosx-arm64")
                urlopen.assert_not_called()
            self.assertFalse(stale.parent.exists())

    def test_candidate_inputs_use_separate_version_and_native_resource(self):
        previous = {name: getattr(runtime, name) for name in
                    ("SOURCE_LOCK", "PATCH_FILE", "VERSION", "RELEASE_TAG")}
        self.addCleanup(lambda: [setattr(runtime, name, value) for name, value in previous.items()])
        canonical_bytes = runtime.SOURCE_LOCK.read_bytes()
        candidate_lock, candidate_patch = self.candidate_inputs()
        runtime.select_inputs(candidate_lock, candidate_patch)
        self.assertEqual("152.0.6-hb99", runtime.VERSION)
        self.assertTrue(runtime.RELEASE_TAG.endswith("+heartbeat-hb99"))
        self.assertEqual({}, runtime.lock()["nativeArtifacts"])
        self.assertEqual(canonical_bytes, previous["SOURCE_LOCK"].read_bytes())

    def test_candidate_rejects_patch_mismatch_without_changing_active_inputs(self):
        previous = runtime.SOURCE_LOCK
        candidate_lock, _ = self.candidate_inputs()
        with self.assertRaisesRegex(ValueError, "differs from selected source lock"):
            runtime.select_inputs(candidate_lock, runtime.PATCH_FILE)
        self.assertEqual(previous, runtime.SOURCE_LOCK)

    def candidate_inputs(self):
        candidate = json.loads(runtime.SOURCE_LOCK.read_text())
        candidate.update(artifactVersion="152.0.6-hb99", nativeArtifacts={})
        candidate.pop("apiArtifact", None)
        patch_file = self.write("candidate.patch", runtime.PATCH_FILE.read_bytes() + b"\n")
        candidate["patchSha256"] = runtime.sha256(patch_file)
        lock_file = self.write("candidate.json", json.dumps(candidate).encode())
        return lock_file, patch_file

    def test_windows_archive_matches_jcefmaven_resource_and_is_reproducible(self):
        source = self.root / "source"
        for name in ["jcef.dll", "libcef.dll", "jcef_helper.exe", "icudtl.dat", "resources.pak", "locales/en-US.pak"]:
            self.write("source/jcef_build/native/Release/" + name)
        self.write("source/LICENSE.txt")
        cef = runtime.lock()["cefVersion"]
        self.write(f"source/third_party/cef/cef_binary_{cef}_windows64/LICENSE.txt")
        self.write("source/heartbeat-api-classes/org/cef/CefRequestContextSettings.class")
        runtime.package(source, "windows-amd64", self.root / "first")
        name = f"jcef-natives-windows-amd64-{runtime.VERSION}.jar"
        api_name = f"jcef-api-{runtime.VERSION}.jar"
        # Synthetic DLL/class bytes have their own published lock, independent of real release assets.
        fixture_lock = dict(runtime.lock(),
                            apiArtifact={"file": api_name, "sha256": runtime.sha256(self.root / "first" / api_name)},
                            nativeArtifacts={"windows-amd64": {
                                "file": name, "sha256": runtime.sha256(self.root / "first" / name)}})
        locked_fixture = patch.object(runtime, "lock", return_value=fixture_lock)
        locked_fixture.start()
        self.addCleanup(locked_fixture.stop)
        runtime.verify_package(source, "windows-amd64", self.root / "first")
        published = dict(runtime.lock(),
                         apiArtifact={"file": api_name, "sha256": "not the published API"},
                         nativeArtifacts={"windows-amd64": {"file": name, "sha256": "native"}})
        with patch.object(runtime, "lock", return_value=published):
            with self.assertRaisesRegex(ValueError, "differs from published lock"):
                runtime.verify_package(source, "windows-amd64", self.root / "first")
        with zipfile.ZipFile(self.root / "first" / name) as jar:
            archive = f"jcef-natives-windows-amd64-{runtime.RELEASE_TAG}.tar.gz"
            self.assertEqual([archive], jar.namelist())
            self.assertEqual(3, jar.getinfo(archive).create_system)
            with tarfile.open(fileobj=io.BytesIO(jar.read(archive)), mode="r:gz") as tar:
                self.assertIn("jcef_helper.exe", tar.getnames())
                self.assertTrue(all(member.mtime == 0 for member in tar.getmembers()))
                meta = json.load(tar.extractfile("build_meta.json"))
                self.assertEqual(runtime.RELEASE_TAG, meta["release_tag"])
        # Packaging starts with a fresh stage; preserve the original to compare bytes.
        (source / "heartbeat-native-stage").rename(source / "previous-stage")
        runtime.package(source, "windows-amd64", self.root / "second")
        self.assertEqual(runtime.sha256(self.root / "first" / name), runtime.sha256(self.root / "second" / name))
        (source / "heartbeat-native-stage/jcef.dll").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "Corrupt cached native file"):
            runtime.verify_package(source, "windows-amd64", self.root / "first")

    def artifacts(self):
        for target in runtime.release_targets(runtime.lock()):
            api = f"jcef-api-{runtime.VERSION}.jar"
            native = f"jcef-natives-{target}-{runtime.VERSION}.jar"
            self.write(f"inputs/{target}/{api}", b"same API")
            self.write(f"inputs/{target}/{native}", target.encode())
            manifest = dict(runtime.lock(), version=runtime.VERSION, platform=target, releaseTag=runtime.RELEASE_TAG,
                            files={api: hashlib.sha256(b"same API").hexdigest(),
                                   native: hashlib.sha256(target.encode()).hexdigest()})
            self.write(f"inputs/{target}/manifest-{target}.json", json.dumps(manifest).encode())

    def test_collection_writes_complete_verified_release_lock(self):
        self.artifacts()
        runtime.collect(self.root / "inputs", self.root / "release")
        lock = json.loads((self.root / "release/source-lock.json").read_text())
        self.assertEqual(runtime.release_targets(lock), set(lock["nativeArtifacts"]))
        self.assertEqual(runtime.VERSION, lock["artifactVersion"])
        self.assertEqual(len(runtime.release_targets(lock)) + 1, len(list((self.root / "release").glob("*.jar"))))
        for artifact in [lock["apiArtifact"], *lock["nativeArtifacts"].values()]:
            self.assertEqual(artifact["sha256"], runtime.sha256(self.root / "release" / artifact["file"]))

    def test_collection_rejects_corrupt_or_different_api(self):
        self.artifacts()
        self.write(f"inputs/windows-arm64/jcef-api-{runtime.VERSION}.jar", b"different API")
        with self.assertRaisesRegex(ValueError, "Conflicting or corrupt"):
            runtime.collect(self.root / "inputs", self.root / "release")

    def test_collection_rejects_missing_architecture(self):
        self.artifacts()
        (self.root / "inputs/windows-arm64/manifest-windows-arm64.json").unlink()
        with self.assertRaisesRegex(ValueError, "every locked platform"):
            runtime.collect(self.root / "inputs", self.root / "release")

    def test_linux_candidate_requires_five_hosts_without_rewriting_published_lock(self):
        canonical = runtime.SOURCE_LOCK.read_bytes()
        self.select_linux_candidate()
        self.assertEqual(set(runtime.TARGETS), runtime.release_targets(runtime.lock()))
        runtime.cef_distribution(runtime.lock(), "linux-amd64")
        self.artifacts()
        missing = self.root / "inputs/linux-amd64/manifest-linux-amd64.json"
        data = missing.read_bytes()
        missing.unlink()
        with self.assertRaisesRegex(ValueError, "every locked platform"):
            runtime.collect(self.root / "inputs", self.root / "release")
        missing.write_bytes(data)
        runtime.collect(self.root / "inputs", self.root / "release")
        self.assertEqual(6, len(list((self.root / "release").glob("*.jar"))))
        self.assertEqual(canonical, (runtime.HERE / "source-lock.json").read_bytes())

    def test_linux_archive_preserves_helper_mode_and_all_runtime_payloads(self):
        self.select_linux_candidate()
        source = self.root / "source"
        for name in ["libjcef.so", "libcef.so", "jcef_helper", "icudtl.dat", "resources.pak",
                     "v8_context_snapshot.bin", "libEGL.so", "libGLESv2.so", "libvulkan.so.1",
                     "libvk_swiftshader.so", "vk_swiftshader_icd.json", "locales/en-US.pak"]:
            path = self.write("source/jcef_build/native/Release/" + name)
            if name == "jcef_helper":
                path.chmod(0o755)
        self.write("source/LICENSE.txt")
        cef = runtime.lock()["cefVersion"]
        self.write(f"source/third_party/cef/cef_binary_{cef}_linux64/LICENSE.txt")
        self.write("source/heartbeat-api-classes/org/cef/CefApp.class")
        runtime.package(source, "linux-amd64", self.root / "linux")
        with zipfile.ZipFile(self.root / "linux" / f"jcef-natives-linux-amd64-{runtime.VERSION}.jar") as jar:
            archive = f"jcef-natives-linux-amd64-{runtime.RELEASE_TAG}.tar.gz"
            with tarfile.open(fileobj=io.BytesIO(jar.read(archive)), mode="r:gz") as tar:
                self.assertIn("libvulkan.so.1", tar.getnames())
                self.assertIn("locales/en-US.pak", tar.getnames())
                if os.name != "nt":
                    self.assertTrue(tar.getmember("jcef_helper").mode & 0o111)
                self.assertTrue(all(member.mtime == 0 for member in tar.getmembers()))

    def select_linux_candidate(self):
        candidate_lock, candidate_patch = self.candidate_inputs()
        data = json.loads(candidate_lock.read_text())
        data["cefDistributions"]["linux-amd64"] = {
            "file": f"cef_binary_{data['cefVersion']}_linux64.tar.bz2", "sha1": "1" * 40,
        }
        candidate_lock.write_text(json.dumps(data))
        runtime.select_inputs(candidate_lock, candidate_patch)

    def test_linux_formatter_uses_the_locked_linux_executable(self):
        payload = b"linux formatter"
        expected = hashlib.sha1(payload).hexdigest()
        self.write("tools/buildtools/linux64/clang-format.sha1", expected.encode())
        with patch.object(runtime.urllib.request, "urlopen", return_value=io.BytesIO(payload)):
            runtime.prepare_formatter(self.root, "linux-amd64")
        self.assertEqual(payload, (self.root / "tools/buildtools/linux64/clang-format").read_bytes())

    def test_patch_lock_rejects_unreviewed_patch(self):
        with patch.object(runtime, "sha256", return_value="bad"):
            with self.assertRaisesRegex(ValueError, "patch differs"):
                runtime.lock()

    def test_request_flags_are_checked_against_downloaded_cef_headers(self):
        native = "UR_FLAG_NONE = 0, UR_FLAG_ALLOW_STORED_CREDENTIALS = 1 << 3,"
        java = "public static final int UR_FLAG_NONE = 0; int UR_FLAG_ALLOW_STORED_CREDENTIALS = 1 << 3;"
        runtime.verify_request_flags(java, native)
        with self.assertRaisesRegex(ValueError, "pinned CEF ABI"):
            runtime.verify_request_flags(java.replace("1 << 3", "1 << 1"), native)
        with self.assertRaisesRegex(ValueError, "pinned CEF ABI"):
            runtime.verify_request_flags(java, "")

    def test_api_order_is_identical_with_windows_and_posix_path_semantics(self):
        names = ["org/cef/browser/CefBrowser_N.class", "org/cef/browser/CefBrowserWr.class"]
        windows = [path.as_posix() for path in runtime.canonical_paths(map(PureWindowsPath, names))]
        posix = [path.as_posix() for path in runtime.canonical_paths(map(PurePosixPath, names))]
        self.assertEqual(sorted(names), windows)
        self.assertEqual(posix, windows)

    def test_jar_bytes_are_independent_of_host_zip_defaults_and_zlib(self):
        archives = []
        for platform in ["win32", "darwin"]:
            output = io.BytesIO()
            with patch.object(zipfile.sys, "platform", platform):
                with zipfile.ZipFile(output, "w") as jar:
                    runtime.zip_entry(jar, "org/cef/CefApp.class", b"same compiled bytecode")
            archives.append(output.getvalue())
        self.assertEqual(archives[0], archives[1])
        with zipfile.ZipFile(io.BytesIO(archives[0])) as jar:
            entry = jar.infolist()[0]
            self.assertEqual(zipfile.ZIP_STORED, entry.compress_type)
            self.assertEqual((1980, 1, 1, 0, 0, 0), entry.date_time)
            self.assertEqual(0o100644 << 16, entry.external_attr)
            self.assertEqual(b"", entry.comment)

    def test_formatter_requires_upstream_hash_before_writing_executable(self):
        expected = hashlib.sha1(b"formatter").hexdigest()
        self.write("tools/buildtools/mac/clang-format.arm64.sha1", expected.encode())
        with patch.object(runtime.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong")):
            with self.assertRaisesRegex(ValueError, "Formatter checksum differs"):
                runtime.prepare_formatter(self.root, "macosx-arm64")
        self.assertFalse((self.root / "tools/buildtools/mac/clang-format").exists())
        with patch.object(runtime.urllib.request, "urlopen", return_value=io.BytesIO(b"formatter")):
            runtime.prepare_formatter(self.root, "macosx-arm64")
        self.assertEqual(b"formatter", (self.root / "tools/buildtools/mac/clang-format").read_bytes())


if __name__ == "__main__":
    unittest.main()
