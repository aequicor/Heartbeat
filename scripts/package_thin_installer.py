#!/usr/bin/env python3
"""Build a native-only desktop image or Debian installer; never copy a JVM image into a thin package."""

import argparse
import io
import json
import os
import plistlib
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

APP_NAME = "Heartbeat"
METADATA = "bootstrap-installer.json"
LINUX_DEPENDENCIES = (
    "libc6 (>= 2.35), zlib1g, libgtk-3-0t64 | libgtk-3-0, libcurl4t64 | libcurl4, "
    "libsecret-1-0, libatomic1, libnss3, libnspr4, libx11-6, libxcb1, libxext6, "
    "libxrender1, libxtst6, libxrandr2, libxcomposite1, libxdamage1, libxfixes3, "
    "libgbm1, libdrm2, libxkbcommon0, libasound2t64 | libasound2"
)


def validate_native_binary(executable: Path, system: str, arch: str) -> None:
    """Check the executable ABI, including the deliberately x64 launcher of a Windows ARM64 payload."""
    if executable.is_symlink() or not executable.is_file():
        raise ValueError("native executable must be a regular file")
    with executable.open("rb") as source:
        header = source.read(64)
        if system == "windows":
            if header[:2] != b"MZ" or len(header) != 64:
                raise ValueError("expected a Windows PE launcher")
            source.seek(struct.unpack_from("<I", header, 60)[0])
            pe = source.read(6)
            if pe[:4] != b"PE\0\0" or pe[4:] != b"\x64\x86":
                raise ValueError("Windows launcher must be x64, including for ARM64 payloads")
        elif system == "macos":
            expected = {"arm64": 0x0100000C, "x64": 0x01000007}[arch]
            if len(header) < 16 or struct.unpack_from("<I", header)[0] != 0xFEEDFACF:
                raise ValueError("expected a 64-bit Mach-O launcher")
            if struct.unpack_from("<I", header, 4)[0] != expected or struct.unpack_from("<I", header, 12)[0] != 2:
                raise ValueError("Mach-O launcher architecture/type mismatch")
        elif system == "linux":
            if arch != "x64" or header[:6] != b"\x7fELF\x02\x01" or len(header) < 20:
                raise ValueError("expected a Linux x64 ELF launcher")
            if struct.unpack_from("<H", header, 18)[0] != 62:
                raise ValueError("ELF launcher architecture mismatch")
        else:
            raise ValueError("unsupported installer OS")


def copy_file(source: Path, target: Path, mode: int = 0o644) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    target.chmod(mode)


def metadata_path(image: Path, system: str) -> Path:
    return image / {"macos": "Contents/Resources", "windows": ".", "linux": "share/heartbeat"}[system] / METADATA


def prepare_image(executable: Path, icons: Path, destination: Path, system: str, arch: str, version: str) -> None:
    """Prepare just the native binary, public installer metadata and artwork before replacing an earlier build."""
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("invalid installer version")
    if arch not in ("x64", "arm64"):
        raise ValueError("unsupported payload architecture")
    validate_native_binary(executable, system, arch)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if not destination.is_dir() or destination.is_symlink():
            raise ValueError("thin image destination must be a directory")
        if any(destination.iterdir()) and not metadata_path(destination, system).is_file():
            raise ValueError("refusing to overwrite a directory not produced by the thin image packager")
    with tempfile.TemporaryDirectory(prefix="heartbeat-thin-", dir=destination.parent) as temporary:
        image = Path(temporary) / "image"
        image.mkdir()
        if system == "macos":
            copy_file(executable, image / "Contents/MacOS/Heartbeat", 0o755)
            copy_file(icons / "heartbeat.icns", image / "Contents/Resources/heartbeat.icns")
            plist = {
                "CFBundleExecutable": APP_NAME, "CFBundleIdentifier": "io.aequicor.heartbeat.selector",
                "CFBundleName": APP_NAME, "CFBundleDisplayName": APP_NAME, "CFBundlePackageType": "APPL",
                "CFBundleVersion": version, "CFBundleShortVersionString": version,
                "CFBundleIconFile": "heartbeat.icns", "NSHighResolutionCapable": True,
                "LSMinimumSystemVersion": "12.0", "NSHumanReadableCopyright": "Aequicor",
            }
            (image / "Contents/Info.plist").write_bytes(plistlib.dumps(plist, fmt=plistlib.FMT_XML))
            (image / "Contents/PkgInfo").write_bytes(b"APPL????")
        elif system == "windows":
            copy_file(executable, image / "Heartbeat.exe", 0o755)
            copy_file(icons / "heartbeat.ico", image / "Heartbeat.ico")
        else:
            copy_file(executable, image / "bin/Heartbeat", 0o755)
            copy_file(icons / "heartbeat.png", image / "share/heartbeat/heartbeat.png")
        metadata = metadata_path(image, system)
        metadata.parent.mkdir(parents=True, exist_ok=True)
        metadata.write_text(json.dumps({
            "schema": 1, "version": version, "channel": "release", "payloadTarget": f"{system}-{arch}",
            "nativeArchitecture": "x64" if system == "windows" else arch,
            "bootstrapPublicKeys": "embedded", "runtime": "downloaded-on-first-launch",
        }, indent=2) + "\n", encoding="utf-8")
        metadata.chmod(0o644)
        image.chmod(0o755)
        for directory in (p for p in image.rglob("*") if p.is_dir()):
            directory.chmod(0o755)
        validate_image(image, system, arch, version)
        if destination.exists():
            shutil.rmtree(destination)
        image.rename(destination)


def tar_bytes(root: Path) -> bytes:
    """Deterministic root-owned Debian archive, without following symlinks."""
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:xz", format=tarfile.GNU_FORMAT) as archive:
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                raise ValueError("unexpected link in thin Debian staging")
            info = archive.gettarinfo(str(path), "./" + path.relative_to(root).as_posix())
            info.mode = 0o755 if path.is_dir() or path.stat().st_mode & 0o111 else 0o644
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = "root"
            if path.is_file():
                with path.open("rb") as source:
                    archive.addfile(info, source)
            else:
                archive.addfile(info)
    return output.getvalue()


def ar_member(name: str, data: bytes) -> bytes:
    header = f"{name + '/':<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode("ascii")
    return header + data + (b"\n" if len(data) % 2 else b"")


def validate_image(image: Path, system: str, arch: str, version: str) -> None:
    """Only the approved native files can enter an installer, including after an incremental build."""
    expected = {
        "macos": {"Contents/MacOS/Heartbeat", "Contents/Resources/heartbeat.icns", "Contents/Info.plist",
                  "Contents/PkgInfo", "Contents/Resources/" + METADATA},
        "windows": {"Heartbeat.exe", "Heartbeat.ico", METADATA},
        "linux": {"bin/Heartbeat", "share/heartbeat/heartbeat.png", "share/heartbeat/" + METADATA},
    }[system]
    paths = list(image.rglob("*"))
    actual = {p.relative_to(image).as_posix() for p in paths if p.is_file()}
    expected_directories = {parent.as_posix() for file in expected for parent in Path(file).parents if parent != Path(".")}
    actual_directories = {p.relative_to(image).as_posix() for p in paths if p.is_dir()}
    if (image.is_symlink() or actual != expected or actual_directories != expected_directories
            or any(p.is_symlink() for p in paths)):
        raise ValueError("thin image contains unexpected files or links")
    metadata = json.loads(metadata_path(image, system).read_text(encoding="utf-8"))
    if (metadata.get("schema") != 1 or metadata.get("version") != version or metadata.get("channel") != "release"
            or metadata.get("payloadTarget") != f"{system}-{arch}"
            or metadata.get("nativeArchitecture") != ("x64" if system == "windows" else arch)):
        raise ValueError("thin image metadata mismatch")
    executable = {"macos": "Contents/MacOS/Heartbeat", "windows": "Heartbeat.exe", "linux": "bin/Heartbeat"}[system]
    validate_native_binary(image / executable, system, arch)
    if system == "macos":
        plist = plistlib.loads((image / "Contents/Info.plist").read_bytes())
        if plist.get("CFBundleExecutable") != APP_NAME or plist.get("CFBundleShortVersionString") != version:
            raise ValueError("thin bundle metadata mismatch")


def package_dmg(image: Path, destination: Path, version: str, arch: str) -> None:
    """Use a fresh staging directory containing exactly the app and the standard Applications link."""
    if sys.platform != "darwin":
        raise ValueError("DMG packaging requires macOS")
    validate_image(image, "macos", arch, version)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="heartbeat-dmg-", dir=destination.parent) as temporary:
        root = Path(temporary)
        stage = root / "stage"
        stage.mkdir()
        bundle = stage / "Heartbeat.app"
        shutil.copytree(image, bundle)
        subprocess.run(["/usr/bin/codesign", "--force", "--sign", "-", str(bundle)], check=True)
        subprocess.run(["/usr/bin/codesign", "--verify", "--deep", "--strict", str(bundle)], check=True)
        (stage / "Applications").symlink_to("/Applications", target_is_directory=True)
        output = root / "installer.dmg"
        subprocess.run([
            "/usr/bin/hdiutil", "create", "-ov", "-format", "UDZO", "-volname", APP_NAME,
            "-srcfolder", str(stage), str(output),
        ], check=True)
        os.replace(output, destination)


def package_deb(image: Path, destination: Path, version: str) -> None:
    """Package only a previously validated Linux native image; package managers own installation and removal."""
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("invalid Debian version")
    validate_image(image, "linux", "x64", version)
    with tempfile.TemporaryDirectory(prefix="heartbeat-deb-") as temporary:
        root = Path(temporary)
        data, control = root / "data", root / "control"
        shutil.copytree(image, data / "opt/heartbeat")
        copy_file(image / "share/heartbeat/heartbeat.png", data / "usr/share/icons/hicolor/256x256/apps/heartbeat.png")
        desktop = data / "usr/share/applications/heartbeat.desktop"
        desktop.parent.mkdir(parents=True)
        desktop.write_text(
            "[Desktop Entry]\nType=Application\nName=Heartbeat\nComment=Heartbeat AI Studio\n"
            "Exec=/opt/heartbeat/bin/Heartbeat %U\nIcon=heartbeat\nTerminal=false\n"
            "Categories=Development;Utility;\nStartupWMClass=io-aequicor-heartbeat-selector-SelectorMain\n", encoding="utf-8",
        )
        control.mkdir()
        installed_kib = (sum(p.stat().st_size for p in data.rglob("*") if p.is_file()) + 1023) // 1024
        (control / "control").write_text(
            f"Package: heartbeat\nVersion: {version}\nArchitecture: amd64\nMaintainer: Aequicor\nInstalled-Size: {installed_kib}\n"
            f"Depends: {LINUX_DEPENDENCIES}\nSection: devel\nPriority: optional\n"
            "Description: Heartbeat AI Studio\n Native launcher with verified first-launch runtime provisioning.\n",
            encoding="utf-8",
        )
        payload = b"!<arch>\n" + ar_member("debian-binary", b"2.0\n")
        payload += ar_member("control.tar.xz", tar_bytes(control)) + ar_member("data.tar.xz", tar_bytes(data))
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary_output = destination.with_suffix(destination.suffix + ".tmp")
        temporary_output.write_bytes(payload)
        if sys.platform.startswith("linux"):
            # Supported Linux hosts are Debian-based; let their actual parser check the archive before publication.
            subprocess.run(["dpkg-deb", "--info", str(temporary_output)], check=True, capture_output=True)
        os.replace(temporary_output, destination)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("image", "deb", "dmg"), required=True)
    parser.add_argument("--os", choices=("windows", "macos", "linux"), required=True)
    parser.add_argument("--arch", choices=("x64", "arm64"), required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--native-executable", type=Path)
    parser.add_argument("--icons", type=Path)
    parser.add_argument("--image", type=Path)
    args = parser.parse_args(argv)
    if args.format == "image":
        if args.native_executable is None or args.icons is None:
            parser.error("image requires --native-executable and --icons")
        prepare_image(args.native_executable, args.icons, args.destination, args.os, args.arch, args.version)
    elif args.format == "deb":
        if args.os != "linux" or args.arch != "x64" or args.image is None:
            parser.error("deb requires Linux x64 --image")
        package_deb(args.image, args.destination, args.version)
    else:
        if args.os != "macos" or args.image is None:
            parser.error("dmg requires macOS --image")
        package_dmg(args.image, args.destination, args.version, args.arch)
    print(f"Thin {args.format}: {args.destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
