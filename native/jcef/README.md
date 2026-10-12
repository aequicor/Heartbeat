# Patched JCEF runtime

Heartbeat builds Java-CEF `17e805ad387d89839fdd2b864466b6510b2a783c` with CEF
`152.0.6+g708dc14+chromium-152.0.7977.83`. `source-lock.json` pins the source and
SHA-256 of `request-context-settings.patch`. Upstream files retain their BSD license.

The patch exposes all five CEF request-context settings and independently owned
Java wrappers sharing a profile context. Each browser needs its own wrapper:
closing a browser disposes that wrapper. Close every browser first, then call
`anchor.dispose(CefCompletionCallback)` to flush profile cookies and release the
anchor on the CEF UI thread. Completion runs on a subsequent UI task; callers
must still account for asynchronous OS file handle release before deleting data.

The same patch reserves browser creation before queuing native work, preventing
duplicate native browsers for one Java wrapper on macOS. `onAfterClose` runs after
Java context/browser cleanup and client-registry removal; `onCreationFailed` is a
terminal notification after cleanup of an uncreated wrapper. A context-aware
`CefURLRequest.create(request, client, context)` overload permits bounded downloads
through the same Chromium profile and its resource-request handler. A disposed
context fails rather than falling back to the global profile. Keep the context
alive until request completion, then dispose the request before its context.

## Build and verify

[Patched JCEF runtime](../../.github/workflows/jcef-runtime.yml) runs on pushes to
`feat/embedded-webview-browser` affecting this directory or the workflow, and via
`workflow_dispatch`. Four native hosted runners build and test matching Java/JNI:

| Target | Runner | Toolchain |
| --- | --- | --- |
| Windows x64 | `windows-2022` | MSVC native x64, Microsoft JDK 21 x64 |
| Windows ARM64 | `windows-11-arm` | MSVC native ARM64, Microsoft JDK 21 ARM64 |
| macOS x64 | `macos-15-intel` | Xcode command-line tools, Microsoft JDK 21 x64 |
| macOS ARM64 | `macos-15` | Xcode command-line tools, Microsoft JDK 21 ARM64 |

Runner labels and architectures are listed in the
[official hosted runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
Windows ARM64 uses the installed native Visual Studio toolchain selected with
`vswhere`; it does not link against an x64 JDK. The workflow installs Ninja/Ant
on macOS and pins JDK 21.0.10 and Python dependency `six==1.17.0`.

For a local build, install Python 3.13, Git, JDK 21.0.10, CMake, Ninja and the native
C++ SDK. macOS also requires Ant. Run Windows commands from the matching MSVC
Developer Command Prompt. Use a fresh source directory for each architecture:

```shell
python -m pip install six==1.17.0
python -m unittest discover -s native/jcef -p "test_*.py" -v
python native/jcef/build_runtime.py prepare
python native/jcef/build_runtime.py build --target windows-amd64
python native/jcef/build_runtime.py package --target windows-amd64
```

Substitute another target from the table when running on its matching host.
`prepare` checks the source SHA and patch hash before applying the patch. Upstream
CMake downloads the exact CEF distribution and verifies its published SHA-1;
packaging records SHA-256 for every produced artifact and native runtime file.
The Java API is compiled from the same patched source as JNI with `--release 8`.
On macOS upstream Ant also compiles its private AWT bridge, shipped in that
platform's native jar. Keep the native jar on the Java classpath.

The native probe in [verification](verification/README.md) exercises persistence
in separate processes, profile isolation, sibling-tab ownership and clearing.
Its reports are CI artifacts. A successful build alone does not establish native
behavior or replace Heartbeat's actual Compose/native UI checks.

## Artifacts and publication

Each matrix job uploads `jcef-<target>` containing two jars and a platform manifest.
The collection job requires all four targets, rejects inconsistent API jars and
mismatched checksums/source versions, then uploads `jcef-release-152.0.6-hb1`:

- `jcef-api-152.0.6-hb1.jar`
- `jcef-natives-<target>-152.0.6-hb1.jar` for each target
- `SHA256SUMS`, `runtime-manifest.json`, and completed `source-lock.json`

The native jar contains a tar.gz resource named exactly as jcefmaven expects:
`jcef-natives-<target>-<release_tag>.tar.gz`. Both API `build_meta.json` and native
metadata use the unique release tag
`jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83+heartbeat-hb1`.
This prevents reuse of an unpatched extraction cache. Native archives include
resources, locales, helper executables/apps and licenses. macOS symlinks and
executable modes are preserved. Jar entries and archive timestamps are normalized;
CI checks that all four shared API builds are byte-identical.

The workflow creates a draft prerelease after all four native probes and collection
checks pass. Publication follows inspection of the verified five jars and
manifests as immutable assets of the GitHub release tag `152.0.6-hb1`. Copy the
completed source lock into this directory, and pin actual jar hashes in Gradle
verification metadata. Coordinates are `io.aequicor.heartbeat.jcef:<artifact>:152.0.6-hb1`;
Gradle consumes release assets using an Ivy artifact pattern. JOGL/gluegen Java
API dependencies remain explicit dependencies of the application.

Never substitute upstream JNI for this Java API. Never populate missing hashes
with placeholders. Until successful native CI outputs are collected, empty
`nativeArtifacts` means publication and the native release gate are incomplete.
