#!/usr/bin/env python3
"""Check the complete bootstrap release set before making its download URLs public.

Signing verifies the original signature in WriteBootstrapManifest. This gate repeats
verification after artifact transfer and checks the assembled publication set: target,
version, train, URL, digest and size must still match the exact files being uploaded.
"""

import argparse
import base64
import hashlib
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

TARGETS = ("linux-x64", "macos-arm64", "macos-x64", "windows-x64", "windows-arm64")


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


TRUSTED_KEYS = Path(__file__).resolve().parents[1] / "platform-main/native-launcher/bootstrap-trusted-keys.txt"


def verify_signature(manifest: Path, trusted_keys: Path) -> None:
    keys = [line.strip() for line in trusted_keys.read_text().splitlines()
            if line.strip() and not line.startswith("#")]
    if not 1 <= len(keys) <= 4:
        raise ValueError("Expected one to four pinned bootstrap keys")
    with tempfile.TemporaryDirectory(prefix="heartbeat-bootstrap-verify-") as temporary:
        public = Path(temporary) / "public.der"
        for key in keys:
            public.write_bytes(base64.b64decode(key, validate=True))
            result = subprocess.run([
                "openssl", "pkeyutl", "-verify", "-pubin", "-keyform", "DER", "-inkey", str(public),
                "-rawin", "-in", str(manifest), "-sigfile", str(manifest.with_suffix(".json.sig")),
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if result.returncode == 0:
                return
    raise ValueError(f"{manifest.name}: signature does not match a pinned bootstrap key")


def verify(directory: Path, version: str, train: str, targets=TARGETS,
           trusted_keys: Path = TRUSTED_KEYS, local_signing: bool = False) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("invalid release version")
    for target in targets:
        system, arch = target.split("-")
        manifest = directory / f"bootstrap-{target}.json"
        body = json.loads(manifest.read_text(encoding="utf-8"))
        expected = {"schema": 1, "version": version, "channel": "release", "os": system, "arch": arch}
        if any(body.get(key) != value for key, value in expected.items()):
            raise ValueError(f"{target}: manifest identity mismatch")
        if len(manifest.with_suffix(".json.sig").read_bytes()) != 64:
            raise ValueError(f"{target}: invalid Ed25519 signature length")
        if not local_signing:
            verify_signature(manifest, trusted_keys)
        for component in ("runtime", "platform", "seed"):
            name = f"heartbeat-{component}-{version}-{target}.zip"
            artifact = directory / name
            record = body[component]
            url = f"https://github.com/aequicor/Heartbeat/releases/download/v{version}/{name}"
            if record.get("url") != url:
                raise ValueError(f"{target}: {component} URL mismatch")
            if artifact.is_symlink() or not artifact.is_file():
                raise ValueError(f"{target}: missing regular {component} archive")
            if record.get("size") != artifact.stat().st_size or record.get("sha256") != digest(artifact):
                raise ValueError(f"{target}: {component} content mismatch")
        if body["seed"].get("trainId") != train:
            raise ValueError(f"{target}: bootstrap train mismatch")
        with zipfile.ZipFile(directory / f"heartbeat-seed-{version}-{target}.zip") as seed:
            # Bound metadata reads even if an archive is malformed; no extraction occurs.
            with seed.open("manifest.json") as source:
                data = source.read(1024 * 1024 + 1)
            if len(data) > 1024 * 1024 or json.loads(data).get("train") != train:
                raise ValueError(f"{target}: seed train mismatch")
        print(f"{target}: bootstrap publication set verified")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--train", required=True)
    parser.add_argument("--target", action="append", choices=TARGETS)
    parser.add_argument("--local-signing", choices=("true", "false"), default="false",
                        help="Only dry-run artifacts may use ephemeral, non-publishable keys")
    args = parser.parse_args()
    if args.local_signing == "true":
        print("DRY RUN: signature trust is not checked; these artifacts must not be published")
    verify(args.directory, args.version, args.train, args.target or TARGETS,
           local_signing=args.local_signing == "true")


if __name__ == "__main__":
    main()
