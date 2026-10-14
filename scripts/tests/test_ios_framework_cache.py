"""The exact-build iOS archive preserves links and rejects stale or unsafe contents."""

import importlib.util
import json
import os
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "ios_framework_cache.py"
spec = importlib.util.spec_from_file_location("ios_framework_cache", SCRIPT)
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class FrameworkArchiveTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "source"
        self.output = self.root / "output"
        self.cached = self.root / "cache"
        self.identity = {"schema": 1, "commit": "a" * 40, "version": "1.0.1"}
        for relative in cache.REQUIRED_FILES:
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(relative)
        (self.source / "Shared.framework/Shared").chmod(0o755)

    def write_metadata(self):
        (self.cached / "metadata.json").write_text(json.dumps({
            "identity": self.identity, "sha256": cache.digest_file(self.cached / "framework.tar"),
        }))

    def test_roundtrip_preserves_binary_permissions_and_relative_symlinks(self):
        (self.source / "Shared.framework/HeaderAlias").symlink_to("Headers/Shared.h")
        cache.pack(self.source, self.cached, self.identity)
        cache.restore(self.cached, self.output, self.identity)
        alias = self.output / "Shared.framework/HeaderAlias"
        self.assertTrue(alias.is_symlink())
        self.assertEqual(os.readlink(alias), "Headers/Shared.h")
        self.assertEqual(alias.read_text(), "Shared.framework/Headers/Shared.h")
        self.assertEqual((self.output / "Shared.framework/Shared").stat().st_mode & 0o777, 0o755)

    def test_changed_identity_and_corruption_are_rejected_before_extraction(self):
        cache.pack(self.source, self.cached, self.identity)
        with self.assertRaisesRegex(ValueError, "different build"):
            cache.restore(self.cached, self.output, {**self.identity, "version": "1.0.2"})
        with (self.cached / "framework.tar").open("ab") as archive:
            archive.write(b"corrupt")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            cache.restore(self.cached, self.output, self.identity)
        self.assertFalse(self.output.exists())

    def test_partial_framework_cannot_be_saved(self):
        (self.source / cache.REQUIRED_FILES[0]).unlink()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            cache.pack(self.source, self.cached, self.identity)

    def test_absolute_source_link_cannot_be_saved(self):
        (self.source / "Shared.framework/alias").symlink_to(self.source / cache.REQUIRED_FILES[0])
        with self.assertRaisesRegex(ValueError, "absolute link"):
            cache.pack(self.source, self.cached, self.identity)

    def test_nonempty_destination_is_preserved(self):
        cache.pack(self.source, self.cached, self.identity)
        self.output.mkdir()
        marker = self.output / "keep"
        marker.write_text("previous output")
        with self.assertRaisesRegex(ValueError, "nonempty"):
            cache.restore(self.cached, self.output, self.identity)
        self.assertEqual(marker.read_text(), "previous output")

    def test_paths_and_links_cannot_escape_even_with_a_matching_archive_hash(self):
        attacks = [
            ("../escaped", None),
            ("/escaped", None),
            ("releaseFramework/escape", "../../escaped"),
            ("releaseFramework/escape", "/escaped"),
        ]
        for name, target in attacks:
            with self.subTest(name=name, target=target):
                cache.pack(self.source, self.cached, self.identity)
                with tarfile.open(self.cached / "framework.tar", "a") as archive:
                    member = tarfile.TarInfo(name)
                    if target:
                        member.type = tarfile.SYMTYPE
                        member.linkname = target
                    archive.addfile(member)
                self.write_metadata()
                with self.assertRaises(ValueError):
                    cache.restore(self.cached, self.output, self.identity)
                self.assertFalse(self.output.exists())
                self.assertFalse((self.root / "escaped").exists())

    def test_archive_cannot_write_through_a_link(self):
        cache.pack(self.source, self.cached, self.identity)
        with tarfile.open(self.cached / "framework.tar", "a") as archive:
            member = tarfile.TarInfo("releaseFramework/alias")
            member.type = tarfile.SYMTYPE
            member.linkname = "Shared.framework"
            archive.addfile(member)
            archive.addfile(tarfile.TarInfo("releaseFramework/alias/file"))
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, "through a symlink"):
            cache.restore(self.cached, self.output, self.identity)


class FrameworkIdentityTests(unittest.TestCase):
    def test_commit_tree_version_apple_toolchain_and_each_jdk_change_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            def git(*args):
                subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)

            git("init", "-q")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-qm", "initial")
            java = root / "jdk"
            java.mkdir()
            (java / "release").write_text('JAVA_VERSION="21.0.1"')
            identity = cache.build_identity(root, "1.0.1", "a" * 64, [java])
            key = cache.identity_key(identity)
            new_version = cache.build_identity(root, "1.0.2", "a" * 64, [java])
            new_apple = cache.build_identity(root, "1.0.1", "b" * 64, [java])
            self.assertNotEqual(key, cache.identity_key(new_version))
            self.assertNotEqual(key, cache.identity_key(new_apple))
            (java / "release").write_text('JAVA_VERSION="21.0.2"')
            changed = cache.build_identity(root, "1.0.1", "a" * 64, [java])
            self.assertNotEqual(key, cache.identity_key(changed))
            (root / "source").write_text("changed source")
            git("add", "source")
            with self.assertRaises(subprocess.CalledProcessError):
                cache.build_identity(root, "1.0.1", "a" * 64, [java])
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "source")
            source_change = cache.build_identity(root, "1.0.1", "a" * 64, [java])
            self.assertNotEqual(changed["tree"], source_change["tree"])
            self.assertNotEqual(cache.identity_key(changed), cache.identity_key(source_change))
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-qm", "same tree")
            next_commit = cache.build_identity(root, "1.0.1", "a" * 64, [java])
            self.assertEqual(source_change["tree"], next_commit["tree"])
            self.assertNotEqual(cache.identity_key(source_change), cache.identity_key(next_commit))


if __name__ == "__main__":
    unittest.main()
