"""Set up the pinned pnpm for release builds and invoke the Kimi builder."""
import base64
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import tomllib
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PNPM_VERSION = "10.33.0"
PNPM_SHA512 = "EFaLtKavtYyes2MNqQzJUWQXq+vT+rvmc58K55VyjaFJHp21pUTHatjrdXD1xLs9bGN7LLQb/c20f6gjyGSTGQ=="
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024


def versions():
    data = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    if data["kimi-build-pnpm"] != PNPM_VERSION:
        raise RuntimeError("Pinned pnpm version changed; update its verified archive hash")
    return data["kimi-build-node"], data["kimi-build-pnpm"]


def extract_pnpm(data, directory):
    actual = base64.b64encode(hashlib.sha512(data).digest()).decode("ascii")
    if actual != PNPM_SHA512:
        raise RuntimeError("pnpm archive checksum mismatch")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = archive.getmembers()
        if any(not item.name.startswith("package/") or item.issym() or item.islnk() for item in members):
            raise RuntimeError("Unexpected pnpm archive entry")
        archive.extractall(directory, members=members, filter="data")
    executable = directory / "package/bin/pnpm.cjs"
    if not executable.is_file():
        raise RuntimeError("pnpm archive lacks its entry point")
    return executable


def build():
    _, version = versions()
    with tempfile.TemporaryDirectory(prefix="heartbeat-kimi-tools-") as temporary:
        url = f"https://registry.npmjs.org/pnpm/-/pnpm-{version}.tgz"
        with urllib.request.urlopen(url, timeout=60) as response:
            archive = response.read(MAX_ARCHIVE_BYTES + 1)
        if len(archive) > MAX_ARCHIVE_BYTES:
            raise RuntimeError("pnpm archive exceeds size limit")
        pnpm = extract_pnpm(archive, Path(temporary))
        node = subprocess.run(["node", "-p", "process.execPath"], check=True, text=True, capture_output=True).stdout.strip()
        subprocess.run([os.sys.executable, str(ROOT / "native/kimi/build_runtime.py"),
                        "--node", node, "--pnpm-js", str(pnpm)], cwd=ROOT, check=True)


def main():
    if os.sys.argv[1:] == ["versions"]:
        node, pnpm = versions()
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"node={node}\npnpm={pnpm}\n")
    elif os.sys.argv[1:] == ["build"]:
        build()
    else:
        raise SystemExit("Expected versions or build")


if __name__ == "__main__":
    main()
