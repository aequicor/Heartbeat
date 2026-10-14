"""Archive a verified iOS framework for retries of the exact commit and release version.

KGP disables Gradle's framework build cache because it does not preserve symlinks.
This separate cache keeps a tar, never changes Kotlin's cache guard, and requires
the same commit/tree, version, active Apple toolchain and JDK release metadata.
The archive is validated in a temporary directory before replacing an empty output.
"""

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path, PurePosixPath


SCHEMA = 1
ARCHIVE_ROOT = "releaseFramework"
REQUIRED_FILES = (
    "Shared.framework/Shared",
    "Shared.framework/Info.plist",
    "Shared.framework/Headers/Shared.h",
    "Shared.framework/Modules/module.modulemap",
)


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def identity_key(identity):
    return hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()


def build_identity(repository, version, apple_fingerprint, java_homes):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("A stable release version is required")
    if not re.fullmatch(r"[a-f0-9]{64}", apple_fingerprint):
        raise ValueError("The active Apple toolchain fingerprint is required")
    subprocess.run(["git", "diff", "--quiet", "HEAD"], cwd=repository, check=True)

    def revision(ref):
        return subprocess.check_output(["git", "rev-parse", ref], cwd=repository, text=True).strip()

    jdk_releases = [digest_file(Path(home) / "release") for home in java_homes]
    if not jdk_releases:
        raise ValueError("The build JDK must be fingerprinted")
    return {
        "schema": SCHEMA,
        "commit": revision("HEAD"),
        "tree": revision("HEAD^{tree}"),
        "version": version,
        "apple_toolchain": apple_fingerprint,
        "jdk_releases": sorted(jdk_releases),
        "host": [platform.system(), platform.machine()],
    }


def validate_framework(directory):
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("Expected a real releaseFramework directory")
    root = directory.resolve()
    for path in directory.rglob("*"):
        if path.is_symlink():
            if Path(os.readlink(path)).is_absolute():
                raise ValueError("Framework contains a non-relocatable absolute link")
            try:
                resolved = path.resolve(strict=True)
            except (OSError, RuntimeError) as error:
                raise ValueError("Framework contains a dangling or cyclic link") from error
            if not resolved.is_relative_to(root):
                raise ValueError("Framework link escapes its directory")
        elif not path.is_dir() and not path.is_file():
            raise ValueError("Framework contains an unsupported file type")
    for relative in REQUIRED_FILES:
        path = directory / relative
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Framework is incomplete: {relative}")


def pack(directory, cache_directory, identity):
    validate_framework(directory)
    cache_directory.mkdir(parents=True, exist_ok=True)
    archive = cache_directory / "framework.tar"
    with tarfile.open(archive, "w", dereference=False) as output:
        output.add(directory, arcname=ARCHIVE_ROOT)
    with tarfile.open(archive, "r:") as saved:
        validate_members(saved.getmembers())
    metadata = {"identity": identity, "sha256": digest_file(archive)}
    (cache_directory / "metadata.json").write_text(json.dumps(metadata, sort_keys=True), encoding="utf-8")


def validate_members(members):
    names = set()
    links = set()
    for member in members:
        path = PurePosixPath(member.name)
        if (path.is_absolute() or ".." in path.parts or "\\" in member.name
                or not path.parts or path.parts[0] != ARCHIVE_ROOT
                or str(path) != member.name or member.name in names):
            raise ValueError("Archive contains an invalid or duplicate path")
        names.add(member.name)
        if not (member.isdir() or member.isfile() or member.issym()):
            raise ValueError("Archive contains an unsupported file type")
        if member.issym():
            if not member.linkname or PurePosixPath(member.linkname).is_absolute():
                raise ValueError("Archive contains an invalid symlink")
            links.add(path)
    for member in members:
        if any(parent in links for parent in PurePosixPath(member.name).parents):
            raise ValueError("Archive writes through a symlink")


def restore(cache_directory, directory, identity):
    metadata = json.loads((cache_directory / "metadata.json").read_text(encoding="utf-8"))
    if metadata.get("identity") != identity:
        raise ValueError("Cached framework belongs to a different build")
    archive = cache_directory / "framework.tar"
    if metadata.get("sha256") != digest_file(archive):
        raise ValueError("Cached framework archive failed its SHA-256 check")
    if directory.is_symlink() or (directory.exists() and (not directory.is_dir() or any(directory.iterdir()))):
        raise ValueError("Refusing to replace a nonempty framework output")
    directory.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=directory.parent) as temporary:
        staging = Path(temporary)
        with tarfile.open(archive, "r:") as source:
            members = source.getmembers()
            validate_members(members)
            # Never let a cached link redirect writes. Links are created only after all regular files.
            for member in members:
                output = staging / member.name
                if member.isdir():
                    output.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(member) as content, output.open("wb") as target:
                        shutil.copyfileobj(content, target)
                    output.chmod(member.mode & 0o777)
            for member in members:
                if member.issym():
                    output = staging / member.name
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.symlink_to(member.linkname)
            for member in reversed(members):
                if member.isdir():
                    (staging / member.name).chmod(member.mode & 0o777)
            restored = staging / ARCHIVE_ROOT
            validate_framework(restored)
            if directory.exists():
                directory.rmdir()
            os.replace(restored, directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    key = commands.add_parser("key")
    key.add_argument("--version", required=True)
    key.add_argument("--apple-fingerprint", required=True)
    key.add_argument("--java-home", action="append", required=True)
    key.add_argument("--identity", type=Path, required=True)
    for command in ("pack", "restore"):
        operation = commands.add_parser(command)
        operation.add_argument("--identity", type=Path, required=True)
        operation.add_argument("--cache-directory", type=Path, required=True)
        operation.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "key":
        identity = build_identity(Path.cwd(), args.version, args.apple_fingerprint, args.java_home)
        args.identity.write_text(json.dumps(identity, sort_keys=True), encoding="utf-8")
        print(identity_key(identity))
    else:
        identity = json.loads(args.identity.read_text(encoding="utf-8"))
        if args.command == "pack":
            pack(args.directory, args.cache_directory, identity)
        else:
            restore(args.cache_directory, args.directory, identity)


if __name__ == "__main__":
    main()
