"""Name the next pre-release: `Heartbeat <next patch version>.<n>`.

Trains (`train-t<sha12>`, prerelease.yml) and full-installer betas (`beta-<version>.<n>`, release.yml with beta) share
one counter: n is the number of pre-releases published since the latest stable release, this one included. Stable
`vMAJOR.MINOR.PATCH` releases are the version history; the next patch comes from release_version.next_version, so
pre-releases never move it. The releases are `gh release list --json tagName,isDraft,isPrerelease,publishedAt` on stdin.
"""

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

_spec = importlib.util.spec_from_file_location("release_version", Path(__file__).with_name("release_version.py"))
release_version = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(release_version)

PRERELEASE_TAG = re.compile(r"train-t[0-9a-f]{12}|beta-(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*)){3}")
STABLE_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def plan(releases, baseline):
    """Return the next pre-release as a dict: stable tag, version, counter, label, title."""
    published = [r for r in releases if not r["isDraft"]]
    stables = [r for r in published if not r["isPrerelease"] and STABLE_TAG.fullmatch(r["tagName"])]
    stable = max(stables, key=lambda r: tuple(map(int, r["tagName"][1:].split("."))), default=None)
    version = release_version.next_version([r["tagName"] for r in stables], baseline, "patch")
    since = stable["publishedAt"] if stable else ""
    count = sum(
        1 for r in published
        if r["isPrerelease"] and PRERELEASE_TAG.fullmatch(r["tagName"]) and r["publishedAt"] > since
    )
    label = f"{version}.{count + 1}"
    return {
        "stable": stable["tagName"] if stable else "",
        "version": version,
        "counter": count + 1,
        "label": label,
        "title": f"Heartbeat {label}",
        "tag": f"beta-{label}",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="the ref a publication is launched from (checked with --dry-run)")
    parser.add_argument("--dry-run", choices=("true", "false"), help="with --ref: only master may publish")
    args = parser.parse_args()
    if args.ref is not None and args.dry_run != "true" and args.ref != "refs/heads/master":
        parser.error("Published pre-releases must be launched from master")
    properties = Path("gradle.properties").read_text(encoding="utf-8")
    baseline = re.search(r"^heartbeat\.version=(.+)$", properties, re.MULTILINE)
    if not baseline:
        parser.error("heartbeat.version is missing from gradle.properties")
    try:
        result = plan(json.load(sys.stdin), baseline[1].strip())
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    for key, value in result.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()
