"""Native-only payload and Debian archive contracts, independent of host packaging tools."""

import importlib.util
import io
import json
import plistlib
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("thin_installer", Path(__file__).parents[1] / "package_thin_installer.py")
THIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(THIN)


class ThinInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.icons = self.root / "icons"
        self.icons.mkdir()
        for extension in ("ico", "icns", "png"):
            (self.icons / ("heartbeat." + extension)).write_bytes(b"test-artwork")

    def binary(self, system, arch="x64"):
        data = bytearray(128)
        if system == "windows":
            data[:2] = b"MZ"
            struct.pack_into("<I", data, 60, 64)
            data[64:70] = b"PE\0\0\x64\x86"
        elif system == "macos":
            struct.pack_into("<IIII", data, 0, 0xFEEDFACF, 0x0100000C if arch == "arm64" else 0x01000007, 0, 2)
        else:
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", data, 18, 62)
        path = self.root / f"native-{system}-{arch}"
        path.write_bytes(data)
        return path

    def image(self, system, arch="x64"):
        image = self.root / ("Heartbeat.app" if system == "macos" else system)
        THIN.prepare_image(self.binary(system, arch), self.icons, image, system, arch, "1.2.3")
        return image

    def test_macos_bundle_has_native_metadata_and_no_runtime_or_seed(self):
        image = self.image("macos", "arm64")
        info = plistlib.loads((image / "Contents/Info.plist").read_bytes())
        self.assertEqual(info["CFBundleExecutable"], "Heartbeat")
        self.assertEqual(info["CFBundleIdentifier"], "io.aequicor.heartbeat.selector")
        self.assertEqual(info["CFBundleShortVersionString"], "1.2.3")
        self.assertEqual(info["LSMinimumSystemVersion"], "12.0")
        files = {p.relative_to(image).as_posix() for p in image.rglob("*") if p.is_file()}
        self.assertEqual(files, {
            "Contents/MacOS/Heartbeat", "Contents/Resources/heartbeat.icns", "Contents/PkgInfo",
            "Contents/Info.plist", "Contents/Resources/bootstrap-installer.json",
        })
        self.assertEqual((image / "Contents/MacOS/Heartbeat").stat().st_mode & 0o777, 0o755)

    def test_dmg_uses_clean_staging_and_rejects_added_payload(self):
        image = self.image("macos", "arm64")
        (image.parent / "unrelated.jar").write_bytes(b"not part of this image")
        destination = self.root / "installer.dmg"

        def create_image(command, check):
            self.assertTrue(check)
            if command[0].endswith("codesign"):
                seal = Path(command[-1]) / "Contents/_CodeSignature/CodeResources"
                if "--force" in command:
                    seal.parent.mkdir(parents=True)
                    seal.write_bytes(b"resource seal")
                else:
                    self.assertTrue(seal.is_file())
                return
            stage = Path(command[command.index("-srcfolder") + 1])
            self.assertTrue((stage / "Heartbeat.app/Contents/_CodeSignature/CodeResources").is_file())
            self.assertEqual({p.name for p in stage.iterdir()}, {"Heartbeat.app", "Applications"})
            self.assertTrue((stage / "Applications").is_symlink())
            self.assertEqual((stage / "Applications").readlink(), Path("/Applications"))
            self.assertFalse((stage / "unrelated.jar").exists())
            Path(command[-1]).write_bytes(b"test disk image")

        with patch.object(THIN.sys, "platform", "darwin"), patch.object(THIN.subprocess, "run", create_image):
            THIN.package_dmg(image, destination, "1.2.3", "arm64")
            self.assertEqual(destination.read_bytes(), b"test disk image")
            (image / "Contents/runtime").mkdir()
            with self.assertRaises(ValueError):
                THIN.package_dmg(image, destination, "1.2.3", "arm64")

    def test_windows_arm64_payload_honestly_uses_x64_native_launcher(self):
        image = self.image("windows", "arm64")
        metadata = json.loads((image / THIN.METADATA).read_text())
        self.assertEqual(metadata["nativeArchitecture"], "x64")
        self.assertEqual(metadata["payloadTarget"], "windows-arm64")
        pe = self.binary("windows")
        content = bytearray(pe.read_bytes())
        content[68:70] = b"\x64\xaa"
        pe.write_bytes(content)
        with self.assertRaises(ValueError):
            THIN.validate_native_binary(pe, "windows", "arm64")

    def test_wrong_abi_and_unowned_output_directory_fail_closed(self):
        with self.assertRaises(ValueError):
            THIN.validate_native_binary(self.binary("macos", "x64"), "macos", "arm64")
        output = self.root / "existing"
        output.mkdir()
        unrelated = output / "keep"
        unrelated.write_text("preserve")
        with self.assertRaises(ValueError):
            THIN.prepare_image(self.binary("linux"), self.icons, output, "linux", "x64", "1.2.3")
        self.assertEqual(unrelated.read_text(), "preserve")

    def test_debian_archive_is_reproducible_native_only_and_root_owned(self):
        image = self.image("linux")
        first, second = self.root / "one.deb", self.root / "two.deb"
        THIN.package_deb(image, first, "1.2.3")
        THIN.package_deb(image, second, "1.2.3")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        data = first.read_bytes()
        self.assertEqual(data[:8], b"!<arch>\n")
        members = {}
        offset = 8
        while offset < len(data):
            header = data[offset:offset + 60]
            self.assertEqual(header[-2:], b"`\n")
            size = int(header[48:58])
            members[header[:16].decode().strip().rstrip("/")] = data[offset + 60:offset + 60 + size]
            offset += 60 + size + size % 2
        self.assertEqual(members["debian-binary"], b"2.0\n")
        with tarfile.open(fileobj=io.BytesIO(members["control.tar.xz"]), mode="r:xz") as archive:
            control = archive.extractfile("./control").read().decode()
            self.assertIn("Architecture: amd64", control)
            self.assertIn("libgtk-3-0t64 | libgtk-3-0", control)
        with tarfile.open(fileobj=io.BytesIO(members["data.tar.xz"]), mode="r:xz") as archive:
            files = {p.name for p in archive if p.isfile()}
            self.assertIn("./opt/heartbeat/bin/Heartbeat", files)
            self.assertIn("./usr/share/applications/heartbeat.desktop", files)
            self.assertFalse(any("runtime/" in p or p.endswith(".jar") for p in files))
            for member in archive.getmembers():
                self.assertEqual((member.uid, member.gid, member.mtime), (0, 0, 0))
        (image / "runtime.jar").write_bytes(b"must not ship")
        with self.assertRaises(ValueError):
            THIN.package_deb(image, first, "1.2.3")


if __name__ == "__main__":
    unittest.main()
