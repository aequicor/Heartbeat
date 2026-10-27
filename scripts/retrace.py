#!/usr/bin/env python3
"""Restore the names in stack traces of an obfuscated bundle train (`-Pheartbeat.trainShrink=obfuscate`).

Input is a log, or the ZIP of the "Application logs" settings page. Every log of a launch states its train in the
line `Launch: train=<id> version=<v>`. The train's `train-manifest.json` lists the jars of that train, and the
pre-release `train-<id>` that first published a jar carries its mapping `mapping-<jar name without .jar>.txt`:

    retrace.py --manifest train-manifest.json --mappings <dir with the mapping files> heartbeat-logs.zip

Without `--manifest` every `mapping-*.txt` of the directory is used; that is right only for the mappings of one
train, because the class names of a bundle repeat from version to version. Class names are unique across the jars of
a train (each artifact draws them from a dictionary of its own), so the mappings of one train can be merged.
Nothing is read from the network and the text of the log is never changed except for class and method names.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path

TRAIN_LINE = re.compile(r"Launch: train=(\S+)")
STACK_LINE = re.compile(r"^(?P<indent>\s*)at (?P<cls>[\w.$]+)\.(?P<method>[\w$<>]+)\((?P<where>[^)]*)\)(?P<rest>.*)$")
CLASS_TOKEN = re.compile(r"[A-Za-z_][\w$]*(?:\.[\w$]+)+")
CLASS_LINE = re.compile(r"^(?P<original>\S+) -> (?P<obfuscated>\S+):$")
METHOD_LINE = re.compile(r"^\s+(?:\d+:\d+:)?(?P<type>\S+) (?P<name>[\w$<>]+)\((?P<args>[^)]*)\)(?::\d+:\d+)? -> (?P<obfuscated>\S+)$")
FIELD_LINE = re.compile(r"^\s+(?P<type>\S+) (?P<name>[\w$]+) -> (?P<obfuscated>\S+)$")
MAPPING_FILE = "mapping-{}.txt"


class Mapping:
    """Class and member names of one or more ProGuard mapping files, by the obfuscated name."""

    def __init__(self) -> None:
        self.classes: dict[str, str] = {}
        self.methods: dict[str, dict[str, list[str]]] = {}

    def read(self, text: str) -> None:
        current: str | None = None
        for line in text.splitlines():
            if not line or line.startswith("#"):
                continue
            if not line[0].isspace():
                match = CLASS_LINE.match(line)
                current = None
                if match:
                    current = match["obfuscated"]
                    self.classes[current] = match["original"]
                continue
            if current is None:
                continue
            method = METHOD_LINE.match(line)
            if method:
                names = self.methods.setdefault(current, {}).setdefault(method["obfuscated"], [])
                if method["name"] not in names:
                    names.append(method["name"])

    def original_class(self, name: str) -> str:
        return self.classes.get(name, name)

    def original_methods(self, owner: str, name: str) -> list[str]:
        return self.methods.get(owner, {}).get(name, [])


def retrace_text(text: str, mapping: Mapping) -> str:
    """Return [text] with class names and the method names of stack frames restored."""
    return "".join(_retrace_line(line, mapping) for line in text.splitlines(keepends=True))


def _retrace_line(line: str, mapping: Mapping) -> str:
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    frame = STACK_LINE.match(body)
    if frame:
        owner = frame["cls"]
        methods = mapping.original_methods(owner, frame["method"])
        method = frame["method"] if not methods else methods[0] if len(methods) == 1 else "(" + "|".join(methods) + ")"
        return (
            f'{frame["indent"]}at {mapping.original_class(owner)}.{method}({frame["where"]})'
            f'{_restore_classes(frame["rest"], mapping)}{ending}'
        )
    return _restore_classes(body, mapping) + ending


def _restore_classes(text: str, mapping: Mapping) -> str:
    return CLASS_TOKEN.sub(lambda token: mapping.original_class(token[0]), text)


def train_of(text: str) -> str | None:
    """The train a log states, or `None` when it states none or `none` (a launch without the platform launcher)."""
    match = TRAIN_LINE.search(text)
    if match is None or match[1] == "none":
        return None
    return match[1]


def jars_of(manifest: dict, train: str | None = None) -> list[str]:
    """Jar names of the train in `train-manifest.json`; [train] is checked when the manifest names one."""
    named = manifest.get("train")
    if train is not None and named is not None and named != train:
        raise ValueError(f"the manifest is of train {named!r}, the log is of train {train!r}")
    jars = [bundle["jar"] for bundle in manifest.get("bundles", []) if isinstance(bundle.get("jar"), str)]
    if not jars:
        raise ValueError("the manifest lists no jars")
    return jars


def load_mapping(directory: Path, jars: list[str] | None) -> Mapping:
    mapping = Mapping()
    if jars is None:
        files = sorted(directory.glob("mapping-*.txt"))
    else:
        files = [directory / MAPPING_FILE.format(jar.removesuffix(".jar")) for jar in jars]
        # contracts are not processed: a jar without a mapping file has nothing to restore
        files = [file for file in files if file.is_file()]
    for file in files:
        mapping.read(file.read_text(encoding="utf-8"))
    return mapping


def read_logs(path: Path) -> list[tuple[str, str]]:
    """`(name, text)` of a log file, or of every text member of a ZIP."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return [
                (info.filename, archive.read(info).decode("utf-8", errors="replace"))
                for info in archive.infolist()
                if not info.is_dir()
            ]
    return [(path.name, path.read_text(encoding="utf-8", errors="replace"))]


def run(args: argparse.Namespace, out: io.TextIOBase) -> int:
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8")) if args.manifest else None
    cache: dict[str | None, Mapping] = {}
    for name, text in read_logs(Path(args.log)):
        train = args.train or train_of(text)
        if train is None:
            out.write(f"=== {name}: no train named, text kept as it is ===\n{text}")
            continue
        if train not in cache:
            jars = jars_of(manifest, train) if manifest else None
            cache[train] = load_mapping(Path(args.mappings), jars)
        out.write(f"=== {name} (train {train}) ===\n{retrace_text(text, cache[train])}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", help="a log file or the ZIP of the Application logs page")
    parser.add_argument("--mappings", required=True, help="directory with the mapping-*.txt files of the train")
    parser.add_argument("--manifest", help="train-manifest.json of the train: picks the mapping of each jar")
    parser.add_argument("--train", help="train id when the log states none")
    args = parser.parse_args(argv)
    try:
        return run(args, sys.stdout)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"retrace: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
