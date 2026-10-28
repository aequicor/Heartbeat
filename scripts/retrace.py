#!/usr/bin/env python3
"""Restore the names in stack traces of an obfuscated bundle train (`-Pheartbeat.trainShrink=obfuscate`).

Input is a log, or the ZIP of the "Application logs" settings page. A launch writes the line
`Launch: train=<id> version=<v>` to its log; the lines after it belong to that train until the next such line (a log
file is appended to by later launches, which may run another train). The train's `train-manifest.json` lists the jars of
that train, and the pre-release `train-<id>` that first published a jar carries its mapping
`mapping-<jar name without .jar>.txt`:

    retrace.py --manifest train-manifest.json --mappings <dir with the mapping files> heartbeat-logs.zip

Without `--manifest` every `mapping-*.txt` of the directory is used; that is right only for the mappings of one
train, because the class names of a bundle repeat from version to version. Class names are unique across the jars of
a train (each artifact draws them from a dictionary of its own), so the mappings of one train can be merged.

Class names in any text and the class and method names of stack frames are restored; the line number of a frame picks
among overloads that were given one name. Simple class names that code writes with `::class.simpleName` are not
restored: the mapping does not say which package they came from. Nothing is read from the network and nothing but
those names is changed in the text (bytes that are not UTF-8 are replaced when the log is read). What could not
be restored is told on stderr.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
import zlib
from pathlib import Path
from typing import NamedTuple, TextIO

TRAIN_LINE = re.compile(r"Launch: train=(\S+)")
# `at [module/][loader//]pkg.Class.method(File.kt:12)`: the prefix is what the JVM prints for modules and loaders.
STACK_LINE = re.compile(
    r"^(?P<indent>\s*)at (?P<prefix>(?:[^\s/(]+/{1,2})*)(?P<cls>[\w.$]+)\.(?P<method>[\w$<>-]+)"
    r"\((?P<where>[^)]*)\)(?P<rest>.*)$"
)
# A dotted name that does not start inside a longer word: every start position of a long run is tried once.
CLASS_TOKEN = re.compile(r"(?<![\w$.])[A-Za-z_][\w$]*(?:\.[\w$]+)+")
CLASS_LINE = re.compile(r"^(?P<original>\S+) -> (?P<obfuscated>\S+):$")
METHOD_LINE = re.compile(
    r"^\s+(?:(?P<start>\d+):(?P<end>\d+):)?(?P<type>\S+) (?P<name>[\w$<>-]+)\((?P<args>[^)]*)\)"
    r"(?::\d+(?::\d+)?)? -> (?P<obfuscated>\S+)$"
)
FRAME_LINE_NUMBER = re.compile(r":(\d+)$")
MAPPING_FILE = "mapping-{}.txt"
UNPROCESSED_ID = "contracts"
BINARY_SNIFF = 8000


class Candidate(NamedTuple):
    """An original method that got an obfuscated name, with the source lines of its body when the mapping says."""

    name: str
    start: int | None
    end: int | None


class Mapping:
    """Class and member names of one or more ProGuard mapping files, by the obfuscated name."""

    def __init__(self) -> None:
        self.classes: dict[str, str] = {}
        self.methods: dict[str, dict[str, list[Candidate]]] = {}

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
                start = int(method["start"]) if method["start"] else None
                end = int(method["end"]) if method["end"] else None
                candidates = self.methods.setdefault(current, {}).setdefault(method["obfuscated"], [])
                candidate = Candidate(method["name"], start, end)
                if candidate not in candidates:
                    candidates.append(candidate)

    def original_class(self, name: str) -> str:
        return self.classes.get(name, name)

    def original_methods(self, owner: str, name: str, line: int | None = None) -> list[str]:
        """Original names behind an obfuscated method name; [line] keeps those whose body contains it."""
        candidates = self.methods.get(owner, {}).get(name, [])
        if line is not None:
            inside = [c for c in candidates if c.start is not None and c.end is not None and c.start <= line <= c.end]
            if inside:
                candidates = inside
        names: list[str] = []
        for candidate in candidates:
            if candidate.name not in names:
                names.append(candidate.name)
        return names


def retrace_text(text: str, mapping: Mapping) -> str:
    """Return [text] with class names and the method names of stack frames restored."""
    return "".join(_retrace_line(line, mapping) for line in text.splitlines(keepends=True))


def _retrace_line(line: str, mapping: Mapping) -> str:
    body = line.rstrip("\r\n")
    ending = line[len(body):]
    frame = STACK_LINE.match(body)
    if frame:
        owner = frame["cls"]
        number = FRAME_LINE_NUMBER.search(frame["where"])
        methods = mapping.original_methods(owner, frame["method"], int(number[1]) if number else None)
        method = frame["method"] if not methods else methods[0] if len(methods) == 1 else "(" + "|".join(methods) + ")"
        return (
            f'{frame["indent"]}at {frame["prefix"]}{mapping.original_class(owner)}.{method}({frame["where"]})'
            f'{_restore_classes(frame["rest"], mapping)}{ending}'
        )
    return _restore_classes(body, mapping) + ending


def _restore_classes(text: str, mapping: Mapping) -> str:
    return CLASS_TOKEN.sub(lambda token: _restore_token(token[0], mapping), text)


def _restore_token(token: str, mapping: Mapping) -> str:
    """The class in [token], or its longest prefix that is one (`pkg.xwp0.INSTANCE`, `pkg.xwp0$Companion`)."""
    if token in mapping.classes:
        return mapping.classes[token]
    for end in range(len(token) - 1, 0, -1):
        if token[end] in ".$" and token[:end] in mapping.classes:
            return mapping.classes[token[:end]] + token[end:]
    return token


def train_of(text: str) -> str | None:
    """The train of the first launch a log states, or `None` when it states none or `none` (no platform launcher)."""
    match = TRAIN_LINE.search(text)
    if match is None or match[1] == "none":
        return None
    return match[1]


def segments(text: str, default: str | None) -> list[tuple[str | None, str]]:
    """Split [text] at each `Launch:` line: `(train, lines)`; the part before the first one belongs to [default]."""
    result: list[tuple[str | None, list[str]]] = [(default, [])]
    for line in text.splitlines(keepends=True):
        launch = TRAIN_LINE.search(line)
        if launch:
            result.append((None if launch[1] == "none" else launch[1], []))
        result[-1][1].append(line)
    return [(train, "".join(lines)) for train, lines in result if lines]


def jars_of(manifest: dict, train: str | None = None) -> list[str]:
    """Jar names of the train in `train-manifest.json`; [train] is checked when the manifest names one."""
    named = manifest.get("train")
    if train is not None and named is not None and named != train:
        raise ValueError(f"the manifest is of train {named!r}, the log is of train {train!r}")
    jars = [bundle["jar"] for bundle in manifest.get("bundles", []) if isinstance(bundle.get("jar"), str)]
    if not jars:
        raise ValueError("the manifest lists no jars")
    return jars


def unprocessed_jars(manifest: dict) -> set[str]:
    """Jars of the train that are not processed and so have no mapping: the contracts."""
    return {b["jar"] for b in manifest.get("bundles", []) if b.get("id") == UNPROCESSED_ID}


def load_mapping(
    directory: Path,
    jars: list[str] | None,
    warn: TextIO | None = None,
    unprocessed: set[str] = frozenset(),
) -> Mapping:
    mapping = Mapping()
    if jars is None:
        files = sorted(directory.glob("mapping-*.txt"))
    else:
        wanted = {jar: directory / MAPPING_FILE.format(jar.removesuffix(".jar")) for jar in jars}
        # a processed jar without its mapping means that its names stay unrestored
        for jar, file in wanted.items():
            if not file.is_file() and jar not in unprocessed and warn is not None:
                print(f"retrace: no mapping {file.name} in {directory}", file=warn)
        files = [file for file in wanted.values() if file.is_file()]
    for file in files:
        mapping.read(file.read_text(encoding="utf-8"))
    if not mapping.classes and warn is not None:
        print(f"retrace: no mapping was loaded from {directory}", file=warn)
    return mapping


def read_logs(path: Path) -> list[tuple[str, str]]:
    """`(name, text)` of a log file, or of every text member of a ZIP; line endings are kept as they are."""
    if zipfile.is_zipfile(path):
        logs = []
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                data = archive.read(info)
                if b"\0" in data[:BINARY_SNIFF]:
                    continue  # a dump or an image, not a log
                logs.append((info.filename, data.decode("utf-8", errors="replace")))
        return logs
    return [(path.name, path.read_bytes().decode("utf-8", errors="replace"))]


def run(args: argparse.Namespace, out: TextIO, warn: TextIO | None = None) -> int:
    warn = warn if warn is not None else sys.stderr
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8")) if args.manifest else None
    cache: dict[str, Mapping | None] = {}
    unprocessed = unprocessed_jars(manifest) if manifest else set()
    # without a manifest the mappings of the directory are one train's and need no train id
    merged = None if manifest else load_mapping(Path(args.mappings), None, warn)
    unknown_warned: set[str] = set()

    def mapping_of(train: str) -> Mapping | None:
        if train not in cache:
            try:
                jars = jars_of(manifest, train) if manifest else None
            except ValueError as error:
                # another train than the manifest's: its names stay as they are, the other launches are still restored
                print(f"retrace: {error}; the lines of train {train} are kept as they are", file=warn)
                cache[train] = None
            else:
                cache[train] = load_mapping(Path(args.mappings), jars, warn, unprocessed)
        return cache[train]

    for name, text in read_logs(Path(args.log)):
        out.write(f"=== {name} ===\n")
        for train, part in segments(text, args.train):
            mapping = mapping_of(train) if train is not None else merged
            if mapping is None:
                if train is None and name not in unknown_warned:
                    unknown_warned.add(name)
                    print(f"retrace: {name}: lines of an unknown train kept as they are (pass --train)", file=warn)
                out.write(part)
            else:
                out.write(retrace_text(part, mapping))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", help="a log file or the ZIP of the Application logs page")
    parser.add_argument("--mappings", required=True, help="directory with the mapping-*.txt files of the train")
    parser.add_argument("--manifest", help="train-manifest.json of the train: picks the mapping of each jar")
    parser.add_argument("--train", help="train id of the lines before the first `Launch:` line of a log")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", newline="")  # a Windows console is not UTF-8, and CRLF stays CRLF
    try:
        return run(args, sys.stdout)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, NotImplementedError, zlib.error) as error:
        # json errors are ValueErrors; a damaged or exotic ZIP member raises the last four
        print(f"retrace: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
