"""Enforce the per-commit review budget without sending source code to a service."""

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import subprocess
import sys

TOKEN_LIMIT = 25_000
TOKEN_WARNING = 20_000
FILE_WARNING = 20
ENCODINGS = ("o200k_base", "cl100k_base")
DIFF_OPTIONS = (
    "--no-ext-diff", "--no-textconv", "--no-color", "--no-relative",
    "--diff-algorithm=myers", "--no-indent-heuristic", "--unified=3",
    "--inter-hunk-context=0", "--find-renames=50%", "-l0", "--full-index",
    "--src-prefix=a/", "--dst-prefix=b/", "--ignore-submodules=none", "--submodule=short",
    f"-O{os.devnull}",
)


def git(*args, cwd=None):
    result = subprocess.run(
        ["git", "-c", "core.quotepath=true", "-c", "diff.suppressBlankEmpty=false", *args],
        cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout


def resolve_commit(ref, cwd=None):
    return git("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}", cwd=cwd).decode().strip()


def select_commits(base, head, cwd=None):
    if git("rev-parse", "--is-shallow-repository", cwd=cwd).strip() == b"true":
        raise RuntimeError("Full Git history is required; fetch with depth 0 or --unshallow.")
    base_sha, head_sha = resolve_commit(base, cwd), resolve_commit(head, cwd)
    git("merge-base", base_sha, head_sha, cwd=cwd)
    return git("rev-list", "--reverse", "--topo-order", f"{base_sha}..{head_sha}", cwd=cwd).decode().splitlines()


def read_diff(commit=None, cwd=None):
    if commit is None:
        command = ("diff", "--cached")
    else:
        command = ("show", "--format=", "--no-notes", "--no-show-signature",
                   "--diff-merges=first-parent", commit)
    patch = git(*command, *DIFF_OPTIONS, "--patch", "--", cwd=cwd)
    numstat = git(*command, *DIFF_OPTIONS, "--no-patch", "--numstat", "-z", "--", cwd=cwd)
    return patch.decode("utf-8", errors="replace"), numstat


def read_stats(numstat):
    files = lines = binaries = 0
    records = iter(numstat.split(b"\0"))
    for record in records:
        if not record:
            continue
        added, deleted, path = record.split(b"\t", 2)
        if not path:  # A rename has two additional NUL-terminated paths.
            next(records)
            next(records)
        files += 1
        if added == b"-":
            binaries += 1
        else:
            lines += int(added) + int(deleted)
    return files, lines, binaries


@dataclass(frozen=True)
class Measurement:
    label: str
    tokens: int
    files: int
    lines: int
    binaries: int

    @property
    def status(self):
        if self.tokens > TOKEN_LIMIT:
            return "FAIL"
        if self.tokens > TOKEN_WARNING or self.files > FILE_WARNING:
            return "WARN"
        return "PASS"


def measure(label, patch, numstat, encodings):
    count = max(len(encoding.encode(patch, disallowed_special=())) for encoding in encodings)
    return Measurement(label, count, *read_stats(numstat))


def report(results):
    table = ["| Commit | Tokens | Files | Lines +/- | Binary files | Result |",
             "|---|---:|---:|---:|---:|---|"]
    for result in results:
        message = (f"{result.label}: {result.tokens}/{TOKEN_LIMIT} tokens, "
                   f"{result.files} files, {result.lines} changed lines, {result.binaries} binary files")
        print(f"{result.status} {message}")
        if os.environ.get("GITHUB_ACTIONS") == "true" and result.status != "PASS":
            level = "error" if result.status == "FAIL" else "warning"
            print(f"::{level} title=Commit size::{message}")
        table.append(f"| {result.label} | {result.tokens} | {result.files} | {result.lines} | "
                     f"{result.binaries} | {result.status} |")
    if not results:
        print("No commits in the selected range.")
        table.append("| No commits | | | | | PASS |")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write("## Commit size\n\n" + "\n".join(table) + "\n")
    return int(any(result.status == "FAIL" for result in results))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--staged", action="store_true", help="Check the Git index before committing")
    mode.add_argument("--commit", help="Check one commit against its first parent")
    mode.add_argument("--base", help="Check every commit in base..head")
    parser.add_argument("--head", default="HEAD", help="Range tip for --base (default: HEAD)")
    args = parser.parse_args(argv)
    try:
        import tiktoken

        if args.base:
            commits = select_commits(args.base, args.head)
        elif args.commit:
            if git("rev-parse", "--is-shallow-repository").strip() == b"true":
                raise RuntimeError("Full Git history is required to check a commit.")
            commits = [resolve_commit(args.commit)]
        else:
            commits = [None]
        encodings = [tiktoken.get_encoding(name) for name in ENCODINGS]
        results = []
        for commit in commits:
            patch, numstat = read_diff(commit)
            results.append(measure(commit[:12] if commit else "staged", patch, numstat, encodings))
        return report(results)
    except ImportError as error:
        print(f"ERROR: {error}. Install scripts/requirements-commit-policy.txt.", file=sys.stderr)
        return 2
    except (OSError, RuntimeError, ValueError) as error:
        print(f"ERROR: commit size check could not complete: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
