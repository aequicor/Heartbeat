#!/usr/bin/env python3
"""Turn the Gradle `bundleManifest` output into a published `train-manifest.json`.

The app (features/app-update) reads `train-manifest.json` with `train`, `platformApi` and bundles with
`id`, `version`, `jar`, `sha256`. A train carries only the jars that changed: a bundle whose SHA-256 equals
the one in the previous train keeps that train's version and jar name, and its jar is not copied to the output.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

TRAIN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
SHA256 = re.compile(r"[0-9a-f]{64}")
MANIFEST_ASSET = "train-manifest.json"


def build_train(built: dict, previous: dict | None, train: str) -> tuple[dict, list[str]]:
    """Return the train manifest and the names of the jars it carries itself."""
    if not TRAIN_ID.fullmatch(train):
        raise ValueError(f"train id {train!r} is not a plain name")
    if previous is not None and previous.get("platformApi") != built["platformApi"]:
        previous = None  # a platform change republishes everything
    known = {b["id"]: b for b in (previous or {}).get("bundles", [])}
    bundles, carried = [], []
    for bundle in sorted(built["bundles"], key=lambda b: b["id"]):
        if not SHA256.fullmatch(bundle["sha256"]):
            raise ValueError(f"bundle {bundle['id']}: bad sha256")
        old = known.get(bundle["id"])
        if old is not None and old["sha256"] == bundle["sha256"]:
            bundles.append({k: old[k] for k in ("id", "version", "jar", "sha256")})
        else:
            bundles.append({k: bundle[k] for k in ("id", "version", "jar", "sha256")})
            carried.append(bundle["jar"])
    return {"train": train, "platformApi": built["platformApi"], "bundles": bundles}, carried


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--built", type=Path, required=True, help="build/bundles/manifest.json from Gradle")
    parser.add_argument("--jars", type=Path, required=True, help="directory with the assembled bundle jars")
    parser.add_argument("--previous", type=Path, help="train-manifest.json of the previous train, if any")
    parser.add_argument("--train", required=True, help="train id")
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    args = parser.parse_args(argv)
    built = json.loads(args.built.read_text(encoding="utf-8"))
    previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous else None
    try:
        manifest, carried = build_train(built, previous, args.train)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    for name in carried:
        shutil.copy2(args.jars / name, args.out / name)
    (args.out / MANIFEST_ASSET).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"train {args.train}: {len(carried)} of {len(manifest['bundles'])} bundles changed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
