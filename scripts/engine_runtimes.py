"""Build and publish immutable native engine dependencies before their bundle train.

The catalog is trusted repository code, not user/project input. Adding a runtime recipe to native/runtimes.json
adds it to the same five-host delivery pipeline without an installer release or another workflow. The adapter
pins a runtime version and resolves its manifest from runtime-<engine>-<version>. Published releases are never
modified; changing build inputs requires a version bump. Drafts may be resumed after an interrupted publication.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOSTS = {"win32-x64": "windows-2025", "win32-arm64": "windows-11-arm",
         "darwin-x64": "macos-15-intel", "darwin-arm64": "macos-15", "linux-x64": "ubuntu-24.04"}
NAME = re.compile(r"[a-z][a-z0-9-]*")
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]*")
SHA = re.compile(r"[0-9a-f]{64}")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(file):
    return json.loads(Path(file).read_text(encoding="utf-8"))


def write_json(file, data):
    file = Path(file)
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def inside(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or path == root.resolve():
        raise ValueError("Runtime recipe path must stay inside the repository")
    return path


def recipes(root=ROOT):
    catalog = read_json(root / "native/runtimes.json")
    if catalog.get("schemaVersion") != 1:
        raise ValueError("Unsupported runtime catalog schema")
    versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    result = []
    for recipe in catalog["engines"]:
        entry = dict(recipe)
        engine = entry["id"]
        version = versions[entry["versionKey"]]
        if not NAME.fullmatch(engine) or not VERSION.fullmatch(version):
            raise ValueError("Invalid runtime identity")
        targets = entry["platforms"]
        if not targets or len(set(targets)) != len(targets) or set(targets) - HOSTS.keys():
            raise ValueError("Unknown or duplicate runtime platform")
        lock = read_json(inside(root, entry["sourceLock"]))
        if not re.fullmatch(r"[0-9a-f]{40}", lock["commit"]):
            raise ValueError("Runtime source must pin a full commit")
        entry.update(version=version, sourceCommit=lock["commit"], node=versions.get(entry.get("nodeVersionKey"), ""))
        if "permissionGate" in lock:
            if type(lock["permissionGate"]) is not int or lock["permissionGate"] <= 0:
                raise ValueError("Invalid runtime permission gate")
            entry["permissionGate"] = lock["permissionGate"]
        entry["tag"] = f"runtime-{engine}-{version}"
        fingerprint = {"recipe": recipe, "versions": {key: versions[key] for key in
                       [entry["versionKey"], *entry.get("toolVersionKeys", [])]}, "inputs": {}}
        for relative in entry["inputs"]:
            source = inside(root, relative)
            files = sorted(source.rglob("*")) if source.is_dir() else [source]
            for file in files:
                # Build outputs, Python caches and builder unit tests do not change runtime bytes.
                if set(file.relative_to(source).parts if source.is_dir() else ()) & {"out", "__pycache__"}:
                    continue
                if file.is_symlink():
                    raise ValueError("Runtime build input cannot be a symlink")
                if file.is_file() and not file.name.startswith("test_"):
                    fingerprint["inputs"][file.relative_to(root).as_posix()] = digest(file.read_bytes())
        entry["buildFingerprint"] = digest(json.dumps(fingerprint, sort_keys=True).encode())
        result.append(entry)
    if len({entry["id"] for entry in result}) != len(result):
        raise ValueError("Duplicate runtime engine")
    return result


def identity(entry):
    keys = ("version", "sourceCommit", "permissionGate", "buildFingerprint")
    return {"schemaVersion": 1, "engine": entry["id"], **{key: entry[key] for key in keys if key in entry}}


def gh(*args, check=True):
    return subprocess.run(["gh", *map(str, args)], cwd=ROOT, text=True, encoding="utf-8", capture_output=True, check=check)


def release(entry, repo):
    response = gh("api", f"repos/{repo}/releases/tags/{entry['tag']}", check=False)
    if response.returncode:
        if "HTTP 404" in response.stderr:
            return None
        raise RuntimeError("Runtime release lookup failed: " + response.stderr)
    return json.loads(response.stdout)


def published(entry, repo, existing):
    if existing is None or existing["draft"]:
        return False
    manifest = json.loads(gh("release", "download", entry["tag"], "--repo", repo,
                             "--pattern", "runtime-manifest.json", "--output", "-").stdout)
    if any(manifest.get(key) != value for key, value in identity(entry).items()):
        raise ValueError(f"Published {entry['tag']} has different build inputs; bump the runtime version")
    if set(manifest.get("platforms", {})) != set(entry["platforms"]):
        raise ValueError("Published runtime is missing platforms")
    assets = {asset["name"]: asset for asset in existing["assets"]}
    for target in entry["platforms"]:
        asset = manifest["platforms"][target]
        name = archive_name(entry, target)
        if asset.get("file") != name or not SHA.fullmatch(asset.get("sha256", "")):
            raise ValueError("Published runtime has invalid asset metadata")
        if asset.get("size", 0) <= 0 or assets.get(name, {}).get("size") != asset["size"]:
            raise ValueError("Published runtime asset is missing or incomplete")
    return True


def make_plan(entries, repo):
    pending = [entry for entry in entries if not published(entry, repo, release(entry, repo))]
    matrix = [{"engine": entry["id"], "target": target, "runner": HOSTS[target], "node": entry["node"]}
              for entry in pending for target in entry["platforms"]]
    return {"matrix": {"include": matrix}, "engines": [entry["id"] for entry in pending]}


def archive_name(entry, target):
    if target not in entry["platforms"]:
        raise ValueError("Platform is not declared by runtime")
    return f"{entry['id']}-{entry['version']}-{target}.zip"


def package(entry, target, output, root=ROOT):
    source = inside(root, entry["output"].format(target=target))
    binary = entry["id"] + (".exe" if target.startswith("win32-") else "")
    files = [source / name for name in [binary, "runtime.properties", "LICENSE"]]
    if any(file.is_symlink() or not file.is_file() for file in files):
        raise ValueError("Runtime output must contain a regular executable, license and manifest")
    properties = dict(line.split("=", 1) for line in files[1].read_text(encoding="utf-8").splitlines() if "=" in line)
    expected = {key: str(value) for key, value in identity(entry).items()
                if key in {"version", "sourceCommit", "permissionGate"}}
    expected["sha256"] = digest(files[0].read_bytes())
    expected["target"] = target
    if any(properties.get(key) != value for key, value in expected.items()):
        raise ValueError("Runtime output does not match the adapter pin or executable hash")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / archive_name(entry, target)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zipped:
        for file in files:
            info = zipfile.ZipInfo(file.name, date_time=(2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100755 if file.name == binary else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            zipped.writestr(info, file.read_bytes())
    asset = {"file": archive.name, "sha256": digest(archive.read_bytes()), "size": archive.stat().st_size}
    write_json(output / "part.json", {**identity(entry), "platforms": {target: asset}})
    return archive


def aggregate(entry, folder):
    folder = Path(folder)
    manifest = {**identity(entry), "platforms": {}}
    archives = []
    for target in entry["platforms"]:
        part = read_json(folder / target / "part.json")
        if any(part.get(key) != value for key, value in identity(entry).items()) or set(part["platforms"]) != {target}:
            raise ValueError("Runtime artifacts have inconsistent provenance")
        asset = part["platforms"][target]
        if asset["file"] != archive_name(entry, target):
            raise ValueError("Unexpected runtime artifact name")
        archive = folder / target / asset["file"]
        if archive.is_symlink() or archive.stat().st_size != asset["size"] or digest(archive.read_bytes()) != asset["sha256"]:
            raise ValueError("Runtime artifact checksum mismatch")
        manifest["platforms"][target] = asset
        archives.append(archive)
    file = folder / "runtime-manifest.json"
    write_json(file, manifest)
    return [file, *archives]


def host_target():
    os_name = {"Windows": "win32", "Darwin": "darwin", "Linux": "linux"}.get(platform.system())
    arch = {"amd64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
    return f"{os_name}-{arch}"


def build(entry, target):
    if target != host_target():
        raise ValueError("Runtime must be built on its native host")
    for command in (entry["test"], entry["build"]):
        subprocess.run([sys.executable if arg == "{python}" else arg for arg in command], cwd=ROOT, check=True)
    package(entry, target, ROOT / "build/runtime-release" / entry["id"] / target)


def publish(entry, repo, folder, commit):
    existing = release(entry, repo)
    if published(entry, repo, existing):
        return
    files = aggregate(entry, folder)
    if existing is None:
        gh("release", "create", entry["tag"], "--repo", repo, "--target", commit, "--draft", "--prerelease",
           "--latest=false", "--title", f"{entry['id']} runtime {entry['version']}",
           "--notes", "Pinned native engine dependency. Installed and verified by Heartbeat; no application installer.")
    # Only an unpublished draft can be resumed; published assets are immutable.
    gh("release", "upload", entry["tag"], *files, "--repo", repo, "--clobber")
    gh("release", "edit", entry["tag"], "--repo", repo, "--draft=false", "--prerelease", "--latest=false")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["plan", "build", "publish"])
    parser.add_argument("--engine")
    parser.add_argument("--target")
    parser.add_argument("--repo", default=os.environ.get("GH_REPO", "aequicor/Heartbeat"))
    parser.add_argument("--folder", type=Path)
    args = parser.parse_args()
    entries = recipes()
    if args.operation == "plan":
        plan = make_plan(entries, args.repo)
        if output := os.environ.get("GITHUB_OUTPUT"):
            with open(output, "a", encoding="utf-8") as file:
                for key, value in plan.items():
                    file.write(f"{key}={json.dumps(value, separators=(',', ':'))}\n")
                file.write(f"pending={'true' if plan['engines'] else 'false'}\n")
        print(json.dumps(plan))
        return
    entry = next((entry for entry in entries if entry["id"] == args.engine), None)
    if entry is None:
        parser.error("Unknown runtime engine")
    if args.operation == "build":
        build(entry, args.target)
    else:
        publish(entry, args.repo, args.folder, os.environ["GITHUB_SHA"])


if __name__ == "__main__":
    main()
