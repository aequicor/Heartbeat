#!/usr/bin/env python3
"""Turn the Gradle `bundleManifest` output into a published `train-manifest.json`.

The app (features/app-update) and the launcher read `train-manifest.json` with `train`, `platformApi` and bundles
with `id`, `version`, `jar`, `sha256` and `url`, the absolute HTTPS address of the jar. A train carries only the
jars that changed: a bundle whose SHA-256 equals the one in the previous train keeps that train's version, jar
name and `url` (the release that first published it), and its jar is not copied to the output. A changed bundle
gets `--base-url` (the download URL of the release being published) plus its jar name. A previous entry without
`url` is published again: the release holding its jar is not known. A change of `platformApi` republishes everything.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

TRAIN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
BUNDLE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
JAR_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar")
SHA256 = re.compile(r"[0-9a-f]{64}")
BASE_URL = re.compile(r"https://[^\s?#/]+/(?:[^\s?#]*/)?")
MANIFEST_ASSET = "train-manifest.json"
FIELDS = ("id", "version", "jar", "sha256")


def check_base_url(url: str, name: str) -> str:
    if not BASE_URL.fullmatch(url):
        raise ValueError(f"{name} {url!r} is not an https URL with a host, ending with '/'")
    return url


def check_bundle(bundle: object, where: str) -> dict:
    """Validate one bundle entry of a built or previous manifest and return it."""
    if not isinstance(bundle, dict):
        raise ValueError(f"{where}: bundle is not an object")
    for key in FIELDS:
        if not isinstance(bundle.get(key), str):
            raise ValueError(f"{where}: bundle field {key!r} is missing")
    name = bundle["id"]
    if not BUNDLE_ID.fullmatch(name):
        raise ValueError(f"{where}: bad bundle id {name!r}")
    if not TRAIN_ID.fullmatch(bundle["version"]):
        raise ValueError(f"{where}: bundle {name}: bad version")
    if not JAR_NAME.fullmatch(bundle["jar"]):
        raise ValueError(f"{where}: bundle {name}: bad jar name")
    if not SHA256.fullmatch(bundle["sha256"]):
        raise ValueError(f"{where}: bundle {name}: bad sha256")
    url = bundle.get("url")
    if url is not None:
        jar = bundle["jar"]
        if not (isinstance(url, str) and url.endswith("/" + jar) and BASE_URL.fullmatch(url[: -len(jar)])):
            raise ValueError(f"{where}: bundle {name}: bad url")
    return bundle


def check_manifest(manifest: object, where: str) -> dict:
    """Validate the shape of a built or previous manifest and return it."""
    if not isinstance(manifest, dict):
        raise ValueError(f"{where}: not an object")
    if not isinstance(manifest.get("platformApi"), int) or isinstance(manifest.get("platformApi"), bool):
        raise ValueError(f"{where}: platformApi is not an integer")
    bundles = manifest.get("bundles")
    if not isinstance(bundles, list):
        raise ValueError(f"{where}: bundles is not a list")
    ids = [check_bundle(b, where)["id"] for b in bundles]
    if len(set(ids)) != len(ids):
        raise ValueError(f"{where}: duplicate bundle id")
    jars = [b["jar"] for b in bundles]
    if len(set(jars)) != len(jars):
        raise ValueError(f"{where}: duplicate jar name")
    return manifest


def build_train(
    built: dict,
    previous: dict | None,
    train: str,
    base_url: str,
) -> tuple[dict, list[str]]:
    """Return the train manifest and the names of the jars it carries itself."""
    if not TRAIN_ID.fullmatch(train):
        raise ValueError(f"train id {train!r} is not a plain name")
    check_base_url(base_url, "base URL")
    check_manifest(built, "built manifest")
    if previous is not None:
        check_manifest(previous, "previous manifest")
        if previous["platformApi"] != built["platformApi"]:
            previous = None  # a platform change republishes everything
    known = {b["id"]: b for b in (previous or {}).get("bundles", [])}
    bundles, carried = [], []
    for bundle in sorted(built["bundles"], key=lambda b: b["id"]):
        old = known.get(bundle["id"])
        if old is not None and old["sha256"] == bundle["sha256"] and "url" in old:
            bundles.append({**{k: old[k] for k in FIELDS}, "url": old["url"]})
            continue
        bundles.append({**{k: bundle[k] for k in FIELDS}, "url": base_url + bundle["jar"]})
        carried.append(bundle["jar"])
    return {"train": train, "platformApi": built["platformApi"], "bundles": bundles}, carried


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--built", type=Path, required=True, help="build/bundles/manifest.json from Gradle")
    parser.add_argument("--jars", type=Path, required=True, help="directory with the assembled bundle jars")
    parser.add_argument("--previous", type=Path, help="train-manifest.json of the previous train, if any")
    parser.add_argument("--train", required=True, help="train id")
    parser.add_argument(
        "--base-url", required=True,
        help="download URL of the release being published, ending with '/' (https://github.com/<repo>/releases/download/<tag>/)",
    )
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    args = parser.parse_args(argv)
    try:
        built = json.loads(args.built.read_text(encoding="utf-8"))
        previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous else None
        manifest, carried = build_train(built, previous, args.train, args.base_url)
    except (ValueError, OSError) as error:  # json.JSONDecodeError is a ValueError
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
