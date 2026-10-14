"""Build the locked JCEF checkout and package the exact resources jcefmaven loads."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
TARGETS = {
    "windows-amd64": ("windows64", "x86_64"),
    "windows-arm64": ("windowsarm64", "arm64"),
    "macosx-amd64": ("macosx64", "x86_64"),
    "macosx-arm64": ("macosarm64", "arm64"),
    "linux-amd64": ("linux64", "x86_64"),
}
LEGACY_TARGETS = frozenset(TARGETS) - {"linux-amd64"}
SOURCE_LOCK = HERE / "source-lock.json"
PATCH_FILE = HERE / "request-context-settings.patch"
CEF_DOWNLOADS = "https://cef-builds.spotifycdn.com/"


def run(*args: object, cwd: Path | None = None) -> None:
    command = [str(arg) for arg in args]
    print("+", subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical_paths(paths):
    # WindowsPath ordering case-folds names, whereas PosixPath does not. ZIP
    # order and javac input order must be identical on all four build hosts.
    return sorted(paths, key=lambda path: path.as_posix())


def lock() -> dict:
    data = json.loads(SOURCE_LOCK.read_text())
    if sha256(PATCH_FILE) != data["patchSha256"]:
        raise ValueError("JCEF patch differs from selected source lock")
    return data


def release_identity(data: dict) -> tuple[str, str]:
    """The artifact version and jcefmaven release tag are derived from the lock, never hard-coded."""
    version = data["artifactVersion"]
    if not re.fullmatch(re.escape(data["cefVersion"].split("+")[0]) + r"-hb[1-9][0-9]*", version):
        raise ValueError("Artifact version must identify the locked CEF release and Heartbeat revision")
    tag = f"jcef-{data['jcefCommit'][:7]}+cef-{data['cefVersion']}+heartbeat-{version.rsplit('-', 1)[1]}"
    return version, tag


def release_targets(data: dict) -> set[str]:
    """Keep published four-host locks reproducible while the next release adds Linux."""
    targets = set(data.get("cefDistributions", {}))
    if targets not in (LEGACY_TARGETS, set(TARGETS)):
        raise ValueError("The source lock must pin every supported release target")
    return targets


VERSION, RELEASE_TAG = release_identity(json.loads(SOURCE_LOCK.read_text()))


def select_inputs(source_lock: Path, patch_file: Path) -> None:
    """Stage a new immutable release while the application still consumes its old lock."""
    global SOURCE_LOCK, PATCH_FILE, VERSION, RELEASE_TAG
    data = json.loads(source_lock.read_text())
    version, tag = release_identity(data)
    if sha256(patch_file) != data["patchSha256"]:
        raise ValueError("JCEF patch differs from selected source lock")
    SOURCE_LOCK, PATCH_FILE, VERSION, RELEASE_TAG = source_lock.resolve(), patch_file.resolve(), version, tag


def prepare(source: Path) -> None:
    data = lock()
    if source.exists():
        raise ValueError(f"A fresh source directory is required: {source}")
    run("git", "clone", "--config", "core.autocrlf=false",
        "https://github.com/chromiumembedded/java-cef.git", source)
    run("git", "checkout", "--detach", data["jcefCommit"], cwd=source)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if actual != data["jcefCommit"]:
        raise ValueError("Unexpected JCEF checkout")
    run("git", "apply", "--check", PATCH_FILE, cwd=source)
    run("git", "apply", PATCH_FILE, cwd=source)


def build(source: Path, target: str) -> None:
    data = lock()
    cef_platform, arch = TARGETS[target]
    prepare_formatter(source, target)
    prepare_cef_distribution(source, target)
    output = source / "jcef_build"
    run("cmake", "-S", source, "-B", output, "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release", f"-DPROJECT_ARCH={arch}",
        f"-DCEF_VERSION={data['cefVersion']}", f"-DPYTHON_EXECUTABLE={sys.executable}")
    cache = (output / "CMakeCache.txt").read_text()
    if f"cef_binary_{data['cefVersion']}_{cef_platform}" not in cache:
        raise ValueError("CMake selected a CEF archive for another architecture")
    cef = source / "third_party/cef" / f"cef_binary_{data['cefVersion']}_{cef_platform}"
    verify_request_flags((source / "java/org/cef/network/CefRequest.java").read_text(),
                         (cef / "include/internal/cef_types.h").read_text())
    run("cmake", "--build", output, "--config", "Release", "--parallel", "3")
    # Compile only the API, identically on all hosts, without platform-specific test resources.
    classes = source / "heartbeat-api-classes"
    classes.mkdir()
    # mac/CefBrowserWindowMac uses macOS-private AWT types. Ant compiles it on Mac;
    # those two classes are shipped in the macOS native jar, not the shared API.
    sources = canonical_paths(p for p in (source / "java/org/cef").rglob("*.java") if p.parent.name != "mac")
    arguments = source / "heartbeat-javac.args"
    arguments.write_text("\n".join('"' + str(p).replace("\\", "/") + '"' for p in sources))
    classpath = os.pathsep.join(str(p) for p in canonical_paths((source / "third_party/jogamp/jar").glob("*.jar")))
    run("javac", "--release", "8", "-encoding", "UTF-8", "-g:source,lines", "-cp", classpath,
        "-d", classes, "@" + str(arguments))


def verify_request_flags(java: str, native: str) -> None:
    """These bit masks cross JNI unchanged; stale Java values change network policy."""
    pattern = r"\b(UR_FLAG_\w+)\s*=\s*(0|1\s*<<\s*\d+)\s*[,;]"
    def masks(text):
        return {name: 0 if value == "0" else 1 << int(value.split("<<")[1])
                for name, value in re.findall(pattern, text)}
    java_masks, native_masks = masks(java), masks(native)
    if not native_masks or any(java_masks.get(name) != value for name, value in native_masks.items()):
        raise ValueError("Java URLRequest flags differ from the pinned CEF ABI")


def prepare_formatter(source: Path, target: str) -> None:
    # CMake always fetches clang-format through a vendored 2019 gsutil whose six
    # importer is incompatible with Python >=3.12. Fetch the identical hash-pinned
    # object over HTTPS first; upstream validates it and skips the gsutil call.
    directory = source / "tools/buildtools"
    if target.startswith("windows"):
        output = directory / "win/clang-format.exe"
        hash_file = directory / "win/clang-format.exe.sha1"
    elif target.startswith("macosx"):
        output = directory / "mac/clang-format"
        arch = "arm64" if target.endswith("arm64") else "x64"
        hash_file = directory / f"mac/clang-format.{arch}.sha1"
    else:
        output = directory / "linux64/clang-format"
        hash_file = directory / "linux64/clang-format.sha1"
    expected = hash_file.read_text().strip()
    if len(expected) != 40 or any(c not in "0123456789abcdef" for c in expected):
        raise ValueError("Invalid upstream formatter checksum")
    with urllib.request.urlopen("https://storage.googleapis.com/chromium-clang-format/" + expected,
                                timeout=120) as response:
        content = response.read()
    if hashlib.sha1(content).hexdigest() != expected:
        raise ValueError("Formatter checksum differs from the locked upstream source")
    output.write_bytes(content)
    output.chmod(0o755)


def cef_distribution(data: dict, target: str) -> tuple[str, str]:
    """The locked CEF binary archive name and SHA-1 (the digest upstream publishes) for one target."""
    entry = data.get("cefDistributions", {}).get(target) or {}
    name, digest = entry.get("file"), entry.get("sha1", "")
    if name != f"cef_binary_{data['cefVersion']}_{TARGETS[target][0]}.tar.bz2":
        raise ValueError(f"The source lock does not pin the CEF distribution for {target}")
    if not re.fullmatch(r"[0-9a-f]{40}", digest):
        raise ValueError(f"Invalid CEF distribution checksum for {target}")
    return name, digest


def prepare_cef_distribution(source: Path, target: str) -> None:
    # Upstream CMake downloads the archive and a .sha1 from the same CDN, so the CDN alone decides what is
    # trusted. Place the archive checked against the lock first: CMake then only extracts it. An existing
    # archive is verified as well. CMake skips extraction when the tree exists, so an earlier tree (possibly
    # cut short by an interrupted extraction, or without its archive) is removed and extracted afresh.
    name, expected = cef_distribution(lock(), target)
    directory = source / "third_party/cef"
    archive = directory / name
    extracted = directory / name.removesuffix(".tar.bz2")
    if extracted.exists():
        shutil.rmtree(extracted)
    if not archive.exists():
        directory.mkdir(parents=True, exist_ok=True)
        partial = archive.with_name(name + ".part")
        digest = hashlib.sha1()
        url = CEF_DOWNLOADS + urllib.parse.quote(name)
        with urllib.request.urlopen(url, timeout=600) as response, partial.open("wb") as stream:
            while chunk := response.read(1 << 20):
                digest.update(chunk)
                stream.write(chunk)
        if digest.hexdigest() != expected:
            partial.unlink()
            raise ValueError("CEF distribution checksum differs from the source lock")
        partial.replace(archive)
    with archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha1").hexdigest() != expected:
            raise ValueError("CEF distribution checksum differs from the source lock")


def metadata(target: str) -> bytes:
    data = lock()
    return (json.dumps({
        "jcef_url": f"https://github.com/chromiumembedded/java-cef/commit/{data['jcefCommit']}",
        "release_tag": RELEASE_TAG,
        "release_url": f"https://github.com/aequicor/Heartbeat/releases/tag/{VERSION}",
        "platform": target,
    }, sort_keys=True, indent=2) + "\n").encode()


def zip_entry(archive: zipfile.ZipFile, name: str, content: bytes | Path) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.create_system = 3  # Unix attributes on every host; ZipInfo defaults differ on Windows.
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o100644 << 16
    with archive.open(info, "w", force_zip64=True) as target:
        if isinstance(content, Path):
            with content.open("rb") as source:
                shutil.copyfileobj(source, target)
        else:
            target.write(content)


def normalized_tar(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = info.gid = info.mtime = 0
    info.uname = info.gname = ""
    return info


def package(source: Path, target: str, destination: Path) -> None:
    data = lock()
    cef_distribution(data, target)
    destination.mkdir(parents=True, exist_ok=True)
    stage = source / "heartbeat-native-stage"
    stage.mkdir()
    output = source / "jcef_build/native/Release"
    cef_platform, _ = TARGETS[target]
    cef = source / "third_party/cef" / f"cef_binary_{data['cefVersion']}_{cef_platform}"
    if target.startswith("windows"):
        for path in sorted(output.iterdir()):
            if path.is_file() and path.suffix in {".dll", ".exe", ".bin", ".dat", ".pak", ".json"}:
                shutil.copy2(path, stage / path.name)
        shutil.copytree(output / "locales", stage / "locales")
        required = ["jcef.dll", "libcef.dll", "jcef_helper.exe", "icudtl.dat", "resources.pak"]
    elif target.startswith("macosx"):
        shutil.copy2(output / "libjcef.dylib", stage / "libjcef.dylib")
        shutil.copytree(cef / "Release/Chromium Embedded Framework.framework",
                        stage / "Chromium Embedded Framework.framework", symlinks=True)
        helpers = list(output.glob("jcef Helper*.app"))
        if len(helpers) < 4:
            raise ValueError("Missing macOS process helper bundles")
        for helper in helpers:
            shutil.copytree(helper, stage / helper.name, symlinks=True)
        required = ["libjcef.dylib", "jcef Helper.app/Contents/MacOS/jcef Helper",
                    "Chromium Embedded Framework.framework/Chromium Embedded Framework"]
    else:
        # CMake places CEF's shared libraries, data, locales and helper next to libjcef.so.
        # Preserve modes: jcefmaven extracts the executable helper directly from this archive.
        for path in sorted(output.iterdir()):
            if path.is_file() and (".so" in path.suffixes or path.suffix in {".bin", ".dat", ".pak", ".json"}
                                   or path.name in {"jcef_helper", "chrome-sandbox"}):
                shutil.copy2(path, stage / path.name)
        shutil.copytree(output / "locales", stage / "locales")
        required = ["libjcef.so", "libcef.so", "jcef_helper", "icudtl.dat", "resources.pak",
                    "v8_context_snapshot.bin", "locales/en-US.pak"]
        if not os.access(stage / "jcef_helper", os.X_OK):
            raise ValueError("Linux JCEF helper is not executable")
    for name in required:
        if not (stage / name).is_file():
            raise ValueError(f"Missing native runtime file: {name}")
    shutil.copy2(source / "LICENSE.txt", stage / "JCEF-LICENSE.txt")
    shutil.copy2(cef / "LICENSE.txt", stage / "CEF-LICENSE.txt")
    for license_file in (source / "third_party/jogamp").glob("*.LICENSE.txt"):
        shutil.copy2(license_file, stage / license_file.name)
    (stage / "build_meta.json").write_bytes(metadata(target))
    archive_name = f"jcef-natives-{target}-{RELEASE_TAG}.tar.gz"
    native_tar = source / archive_name
    with native_tar.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as tar:
            for path in sorted(stage.iterdir()):
                tar.add(path, arcname=path.name, filter=normalized_tar)
    native_jar = destination / f"jcef-natives-{target}-{VERSION}.jar"
    with zipfile.ZipFile(native_jar, "w") as archive:
        zip_entry(archive, archive_name, native_tar)
        if target.startswith("macosx"):
            mac_classes = list((output / "org/cef/browser/mac").glob("*.class"))
            if len(mac_classes) < 2:
                raise ValueError("The macOS AWT browser bridge was not compiled by Ant")
            for path in sorted(mac_classes):
                zip_entry(archive, "org/cef/browser/mac/" + path.name, path)
    api_jar = destination / f"jcef-api-{VERSION}.jar"
    classes = source / "heartbeat-api-classes"
    with zipfile.ZipFile(api_jar, "w") as archive:
        zip_entry(archive, "META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nAutomatic-Module-Name: jcef\r\n\r\n")
        zip_entry(archive, "build_meta.json", metadata("*"))
        zip_entry(archive, "META-INF/JCEF-LICENSE.txt", source / "LICENSE.txt")
        for path in canonical_paths(classes.rglob("*.class")):
            zip_entry(archive, path.relative_to(classes).as_posix(), path)
    manifest = {"version": VERSION, "releaseTag": RELEASE_TAG, "platform": target,
                "jcefCommit": data["jcefCommit"], "cefVersion": data["cefVersion"],
                "patchSha256": data["patchSha256"],
                "files": {p.name: sha256(p) for p in (api_jar, native_jar)},
                "nativeFiles": {p.relative_to(stage).as_posix(): sha256(p)
                                for p in sorted(stage.rglob("*")) if p.is_file() and not p.is_symlink()}}
    (destination / f"manifest-{target}.json").write_text(json.dumps(manifest, indent=2) + "\n")


def collect(artifacts: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    manifests = [json.loads(p.read_text()) for p in sorted(artifacts.rglob("manifest-*.json"))]
    targets = release_targets(lock())
    if {m["platform"] for m in manifests} != targets or len(manifests) != len(targets):
        raise ValueError("Exactly one manifest for every locked platform is required")
    checksums = {}
    for manifest in manifests:
        data = lock()
        if (manifest["patchSha256"] != data["patchSha256"] or manifest["version"] != VERSION
                or manifest["jcefCommit"] != data["jcefCommit"]
                or manifest["cefVersion"] != data["cefVersion"]
                or manifest["releaseTag"] != RELEASE_TAG):
            raise ValueError("Builds came from different inputs")
        expected_files = {f"jcef-api-{VERSION}.jar", f"jcef-natives-{manifest['platform']}-{VERSION}.jar"}
        if set(manifest["files"]) != expected_files:
            raise ValueError("Each platform must supply precisely its native jar and the API jar")
        for name, checksum in manifest["files"].items():
            candidates = list(artifacts.rglob(name))
            if not candidates or any(sha256(p) != checksum for p in candidates):
                raise ValueError(f"Conflicting or corrupt artifact: {name}")
            if name in checksums and checksums[name] != checksum:
                raise ValueError(f"API builds are not identical: {name}")
            checksums[name] = checksum
            shutil.copy2(candidates[0], destination / name)
    (destination / "SHA256SUMS").write_text("".join(f"{digest}  {name}\n" for name, digest in sorted(checksums.items())))
    (destination / "runtime-manifest.json").write_text(json.dumps(manifests, indent=2) + "\n")
    release_lock = lock()
    release_lock["artifactVersion"] = VERSION
    api_name = f"jcef-api-{VERSION}.jar"
    release_lock["apiArtifact"] = {"file": api_name, "sha256": checksums[api_name]}
    release_lock["nativeArtifacts"] = {
        target: {"file": f"jcef-natives-{target}-{VERSION}.jar",
                 "sha256": checksums[f"jcef-natives-{target}-{VERSION}.jar"]}
        for target in sorted(targets)
    }
    (destination / "source-lock.json").write_text(json.dumps(release_lock, indent=2) + "\n")


def verify_package(source: Path, target: str, artifacts: Path) -> None:
    """Validate restored cache bytes before executing JNI or publishing artifacts."""
    manifest = json.loads((artifacts / f"manifest-{target}.json").read_text())
    data = lock()
    for key, expected in {"platform": target, "version": VERSION, "releaseTag": RELEASE_TAG,
                          "jcefCommit": data["jcefCommit"], "cefVersion": data["cefVersion"],
                          "patchSha256": data["patchSha256"]}.items():
        if manifest[key] != expected:
            raise ValueError(f"Cached runtime input mismatch: {key}")
    expected_files = {f"jcef-api-{VERSION}.jar", f"jcef-natives-{target}-{VERSION}.jar"}
    if set(manifest["files"]) != expected_files or not manifest["nativeFiles"]:
        raise ValueError("Incomplete runtime manifest")
    for name, checksum in manifest["files"].items():
        if sha256(artifacts / name) != checksum:
            raise ValueError(f"Corrupt cached artifact: {name}")
    if data.get("nativeArtifacts"):
        for artifact in (data["apiArtifact"], data["nativeArtifacts"][target]):
            if manifest["files"].get(artifact["file"]) != artifact["sha256"]:
                raise ValueError(f"Cached artifact differs from published lock: {artifact['file']}")
    stage = source / "heartbeat-native-stage"
    actual_files = {p.relative_to(stage).as_posix() for p in stage.rglob("*")
                    if p.is_file() and not p.is_symlink()}
    if actual_files != set(manifest["nativeFiles"]):
        raise ValueError("Cached native stage has missing or extra files")
    for name, checksum in manifest["nativeFiles"].items():
        if sha256(stage / name) != checksum:
            raise ValueError(f"Corrupt cached native file: {name}")
    print(f"Verified {target}: API, native jar, and {len(actual_files)} staged files", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "build", "package", "collect", "verify"])
    parser.add_argument("--source", type=Path, default=Path("build/jcef-source"))
    parser.add_argument("--target", choices=TARGETS)
    parser.add_argument("--output", type=Path, default=Path("build/jcef-artifacts"))
    parser.add_argument("--source-lock", type=Path, default=SOURCE_LOCK)
    parser.add_argument("--patch", type=Path, default=PATCH_FILE)
    options = parser.parse_args()
    select_inputs(options.source_lock, options.patch)
    source, output = options.source.resolve(), options.output.resolve()
    if options.command == "prepare":
        prepare(source)
    elif options.command == "collect":
        collect(source, output)
    elif not options.target:
        parser.error("--target is required")
    elif options.command == "build":
        build(source, options.target)
    elif options.command == "verify":
        verify_package(source, options.target, output)
    else:
        package(source, options.target, output)


if __name__ == "__main__":
    main()
