#!/usr/bin/env python3
"""Fail when a desktop installer (DMG, EXE, DEB) exceeds the size budget."""

import argparse
import sys
from pathlib import Path

# Temporary budget until the thin platform of the bundle train ships (target: 50 MiB).
DEFAULT_LIMIT_BYTES = 130 * 1024 * 1024  # 136314880


def check(path: Path, limit: int) -> str | None:
    """Return an error message, or None when the installer fits the limit."""
    if not path.is_file():
        return f"{path}: not a file"
    size = path.stat().st_size
    if size > limit:
        return f"{path}: {size} bytes exceeds the limit of {limit} bytes ({size - limit} over)"
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("installers", nargs="+", type=Path, help="installer files to check")
    parser.add_argument("--limit-bytes", type=int, default=DEFAULT_LIMIT_BYTES)
    args = parser.parse_args(argv)
    errors = [error for path in args.installers if (error := check(path, args.limit_bytes))]
    for error in errors:
        print(error, file=sys.stderr)
    for path in args.installers:
        if path.is_file():
            print(f"{path}: {path.stat().st_size} bytes (limit {args.limit_bytes})")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
