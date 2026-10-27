#!/usr/bin/env python3
"""Assemble the seed of the desktop installers from a published train.

`train-manifest.json` lists every bundle of the train, but the release carries only the jars that changed since the
previous train; the others are named by the `url` of the release that first published them. An installer must start
offline, so its seed holds the whole train: the manifest as published (`manifest.json`) and every jar as
`<jar>.seed`, taken from `--train-dir` or downloaded from its `url` and always checked against the SHA-256 of the
manifest. The `.seed` suffix keeps jpackage from putting the jars on the class path of the installed app.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import urllib.request
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_manifest import MANIFEST_ASSET, check_manifest  # noqa: E402

SEED_SUFFIX = ".seed"
MANIFEST = "manifest.json"
TRAIN_ID = "train"


def download(url: str) -> bytes:
    """Fetch [url] over https; GitHub release assets redirect to their storage host."""
    if not url.startswith("https://"):
        raise ValueError(f"{url!r} is not an https URL")
    with urllib.request.urlopen(url, timeout=120) as response:  # noqa: S310 - https only, checked above
        return response.read()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build_seed(
    manifest: dict,
    train_dir: Path,
    out: Path,
    fetch: Callable[[str], bytes] = download,
) -> list[str]:
    """Write the seed of [manifest] to [out]; return the names of the jars that had to be downloaded."""
    check_manifest(manifest, "train manifest")
    train = manifest.get(TRAIN_ID)
    if not isinstance(train, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", train):
        raise ValueError("train manifest has no valid train id")
    out.mkdir(parents=True, exist_ok=True)
    downloaded = []
    for bundle in manifest["bundles"]:
        name, expected = bundle["jar"], bundle["sha256"]
        local = train_dir / name
        if local.is_file():
            data = local.read_bytes()
        else:
            url = bundle.get("url")
            if not url:
                raise ValueError(f"bundle {bundle['id']}: the jar is not in the train and has no url")
            data = fetch(url)
            downloaded.append(name)
        if sha256(data) != expected:
            raise ValueError(f"bundle {bundle['id']}: {name} does not match the SHA-256 of the manifest")
        (out / (name + SEED_SUFFIX)).write_bytes(data)
    (out / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return downloaded


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--train-dir", type=Path, required=True, help="directory with train-manifest.json and its jars")
    parser.add_argument("--out", type=Path, required=True, help="seed directory to create")
    args = parser.parse_args(argv)
    try:
        manifest = json.loads((args.train_dir / MANIFEST_ASSET).read_text(encoding="utf-8"))
        if args.out.exists():
            leftovers = [p for p in args.out.iterdir() if p.name != MANIFEST and not p.name.endswith(SEED_SUFFIX)]
            if leftovers:
                raise ValueError(f"{args.out} exists and is not a seed directory; refusing to delete it")
            shutil.rmtree(args.out)
        downloaded = build_seed(manifest, args.train_dir, args.out)
    except (ValueError, OSError) as error:  # json.JSONDecodeError and URLError are ValueError / OSError
        print(error, file=sys.stderr)
        return 1
    total = len(manifest["bundles"])
    print(f"seed of train {manifest['train']}: {total} jars, {len(downloaded)} downloaded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
