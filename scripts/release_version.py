"""Choose the next installer version for a manually requested release.

Stable vMAJOR.MINOR.PATCH tags are the version history; unrelated tags and
prereleases do not participate. Before the first tag, heartbeat.version is the
baseline. Desktop packaging and Android versionCode require major 1..999 and
minor/patch 0..999. A failed build does not reserve a tag.
"""

import argparse
import re
import subprocess
from pathlib import Path


VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
LEVELS = ("patch", "minor", "major")


def next_version(tags, baseline, level):
    if level not in LEVELS:
        raise ValueError("Release level must be patch, minor or major")
    versions = [
        tuple(map(int, match.groups()))
        for tag in tags
        if tag.startswith("v") and (match := VERSION.fullmatch(tag[1:]))
    ]
    if versions:
        current = max(versions)
    else:
        match = VERSION.fullmatch(baseline)
        if not match:
            raise ValueError("heartbeat.version must be MAJOR.MINOR.PATCH")
        current = tuple(map(int, match.groups()))
    major, minor, patch = current
    if not (1 <= major <= 999 and 0 <= minor <= 999 and 0 <= patch <= 999):
        raise ValueError("Baseline version is outside installer limits")
    if level == "major":
        result = (major + 1, 0, 0)
    elif level == "minor":
        result = (major, minor + 1, 0)
    else:
        result = (major, minor, patch + 1)
    if any(part > 999 for part in result):
        raise ValueError("Next version exceeds installer limits; choose a higher release level")
    return ".".join(map(str, result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--level", required=True, choices=LEVELS)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--dry-run", required=True, choices=("true", "false"))
    args = parser.parse_args()
    if args.dry_run == "false" and args.ref != "refs/heads/master":
        parser.error("Published releases must be launched from master")
    properties = Path("gradle.properties").read_text(encoding="utf-8")
    baseline = re.search(r"^heartbeat\.version=(.+)$", properties, re.MULTILINE)
    if not baseline:
        parser.error("heartbeat.version is missing from gradle.properties")
    tags = subprocess.run(
        ["git", "tag", "--list", "v*"], check=True, capture_output=True, text=True
    ).stdout.splitlines()
    try:
        version = next_version(tags, baseline[1].strip(), args.level)
    except ValueError as error:
        parser.error(str(error))
    print(f"version={version}")
    print(f"tag=v{version}")


if __name__ == "__main__":
    main()
