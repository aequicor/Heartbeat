"""Upload a verified native build to a draft release without replacing any asset."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def gh(*arguments, check=True):
    return subprocess.run(["gh", *map(str, arguments)], text=True, capture_output=True, check=check)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--version", default="152.0.6-hb3", help="Exact release approved for publication")
    options = parser.parse_args()
    directory = options.artifacts.resolve()
    lock = json.loads((directory / "source-lock.json").read_text())
    version = lock["artifactVersion"]
    targets = {"windows-amd64", "windows-arm64", "macosx-amd64", "macosx-arm64"}
    if version != options.version or set(lock["nativeArtifacts"]) != targets:
        raise ValueError("Unexpected release version or native targets")
    for artifact in [lock["apiArtifact"], *lock["nativeArtifacts"].values()]:
        if Path(artifact["file"]).name != artifact["file"]:
            raise ValueError("Release asset names must be plain filenames")
        with (directory / artifact["file"]).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != artifact["sha256"]:
                raise ValueError("Release asset does not match the verified source lock")
    expected = {lock["apiArtifact"]["file"], *(item["file"] for item in lock["nativeArtifacts"].values()),
                "SHA256SUMS", "source-lock.json", "runtime-manifest.json"}
    if len(expected) != 8 or any(not (directory / name).is_file() for name in expected):
        raise ValueError("A complete verified five-jar release is required")
    repository = options.repository
    existing = gh("release", "view", version, "--repo", repository, "--json", "assets,url", check=False)
    if existing.returncode == 0:
        release = json.loads(existing.stdout)
        names = {asset["name"] for asset in release["assets"]}
        # A failed/partial earlier upload needs human inspection; do not infer that
        # its tag or existing assets belong to this build without a matching lock.
        for required in ("SHA256SUMS", "source-lock.json", "runtime-manifest.json"):
            if required not in names:
                raise ValueError(f"Existing release has no {required}; inspect it before retrying")
        with tempfile.TemporaryDirectory() as temporary:
            for required in ("SHA256SUMS", "source-lock.json", "runtime-manifest.json"):
                gh("release", "download", version, "--repo", repository, "--pattern", required,
                   "--dir", temporary)
                if (Path(temporary) / required).read_bytes() != (directory / required).read_bytes():
                    raise ValueError(f"Immutable release differs: {required}")
        missing = sorted(expected - names)
        if missing:
            gh("release", "upload", version, "--repo", repository, *(directory / name for name in missing))
        print(release["url"])
    else:
        if "release not found" not in existing.stderr.lower():
            raise RuntimeError(existing.stderr)
        result = gh("release", "create", version, "--repo", repository, "--draft", "--prerelease",
                    "--latest=false", "--target", options.commit,
                    "--title", f"Heartbeat JCEF runtime {version}",
                    "--notes-file", Path(__file__).with_name("RELEASE_NOTES.md"),
                    *(directory / name for name in sorted(expected)))
        print(result.stdout)


if __name__ == "__main__":
    main()
