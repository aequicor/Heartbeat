"""Packaging/verification contracts without downloading CEF or requiring a C++ SDK."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_runtime as runtime


class RuntimePackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative, data=b"fixture"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def test_windows_archive_matches_jcefmaven_resource_and_is_reproducible(self):
        source = self.root / "source"
        for name in ["jcef.dll", "libcef.dll", "jcef_helper.exe", "icudtl.dat", "resources.pak", "locales/en-US.pak"]:
            self.write("source/jcef_build/native/Release/" + name)
        self.write("source/LICENSE.txt")
        cef = runtime.lock()["cefVersion"]
        self.write(f"source/third_party/cef/cef_binary_{cef}_windows64/LICENSE.txt")
        self.write("source/heartbeat-api-classes/org/cef/CefRequestContextSettings.class")
        runtime.package(source, "windows-amd64", self.root / "first")
        runtime.verify_package(source, "windows-amd64", self.root / "first")
        name = f"jcef-natives-windows-amd64-{runtime.VERSION}.jar"
        api_name = f"jcef-api-{runtime.VERSION}.jar"
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
        for target in runtime.TARGETS:
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
        self.assertEqual(set(runtime.TARGETS), set(lock["nativeArtifacts"]))
        self.assertEqual(runtime.VERSION, lock["artifactVersion"])
        self.assertEqual(5, len(list((self.root / "release").glob("*.jar"))))
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
        with self.assertRaisesRegex(ValueError, "Exactly four"):
            runtime.collect(self.root / "inputs", self.root / "release")

    def test_patch_lock_rejects_unreviewed_patch(self):
        with patch.object(runtime, "sha256", return_value="bad"):
            with self.assertRaisesRegex(ValueError, "patch differs"):
                runtime.lock()

    def test_cache_identity_ignores_outputs_but_tracks_source_inputs(self):
        inputs = runtime.lock()
        with patch.object(runtime, "lock", return_value=inputs):
            initial = runtime.cache_key()
        published = dict(inputs, apiArtifact={"file": "api.jar", "sha256": "published"},
                         nativeArtifacts={"windows-amd64": {"sha256": "published"}})
        with patch.object(runtime, "lock", return_value=published):
            self.assertEqual(initial, runtime.cache_key())
        for name in ["jcefCommit", "cefVersion", "patchSha256", "artifactVersion"]:
            with patch.object(runtime, "lock", return_value=dict(inputs, **{name: "changed"})):
                self.assertNotEqual(initial, runtime.cache_key())

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
