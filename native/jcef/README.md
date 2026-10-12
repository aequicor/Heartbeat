# Patched JCEF runtime

Heartbeat builds Java-CEF `17e805ad387d89839fdd2b864466b6510b2a783c` with CEF
`152.0.6+g708dc14+chromium-152.0.7977.83`. `source-lock.json` pins the source and
SHA-256 of `request-context-settings.patch`. Upstream files retain their BSD license.

The published **152.0.6-hb2** release is pinned by this source lock and by Gradle
verification metadata. It contains the matching Java API and native libraries
for all four desktop targets. The previous hb1 release remains immutable.

hb2 fixes a confirmed macOS use-after-free in queued visibility updates and the
same resize/close race: Swing paint previously read NSView before dispatching to
AppKit. Both operations now queue a `CefRefPtr<CefBrowser>` and value arguments;
a shared CEF UI helper checks browser validity and the native view registry on
that thread, and resolves the window handle only then. Forced
close removes the view before `OnBeforeClose`, so the registry check is required
in addition to `IsValid()`. A separate native process stresses visibility changes
and resize/paint with immediate close across twenty new tabs before checking
profile disposal. A cancelled reparent operation also releases its callback's
browser reference when the view has already been removed.
Content-view destruction removes a snapshot of its children before detaching
itself from its last owner; no access to `self` follows `removeFromSuperview`.
This path also runs during deallocation, so it must not resurrect the view.
The Java DevTools client also retains early method results until the native
message ID reaches the caller. Closing it fails both operations waiting for an
ID and operations waiting for a response; late callbacks cannot recreate them.
Deterministic fake-browser tests exercise both callback orders without JNI.

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
through the same Chromium profile. Standalone requests do not emit resource-handler
callbacks: Heartbeat uses `UR_FLAG_STOP_ON_REDIRECT`, validates each completed hop,
and explicitly follows accepted HTTP(S) locations within one transfer budget. A disposed
context fails rather than falling back to the global profile. Keep the context
alive until request completion, then dispose the request before its context.

## Build and verify

There is no CI pipeline for this runtime: each release is built, probed and collected
locally, by the maintainer, on four native hosts. Cross-compilation is not used:

| Target | Host | Toolchain |
| --- | --- | --- |
| Windows x64 | Windows x64 | MSVC native x64, Microsoft JDK 21 x64 |
| Windows ARM64 | Windows ARM64 | MSVC native ARM64, Microsoft JDK 21 ARM64 |
| macOS x64 | macOS on Intel | Xcode command-line tools, Microsoft JDK 21 x64 |
| macOS ARM64 | macOS on Apple silicon | Xcode command-line tools, Microsoft JDK 21 ARM64 |

Install Python 3.13, Git, JDK 21.0.10, CMake, Ninja and the native C++ SDK. macOS also
requires Ant. Windows ARM64 uses the installed native Visual Studio toolchain (select it
with `vswhere`); it does not link against an x64 JDK. Run Windows commands from the
matching MSVC Developer Command Prompt. Use a fresh source directory for each build:

```shell
python -m pip install six==1.17.0
python -m unittest discover -s native/jcef -p "test_*.py" -v
python native/jcef/build_runtime.py prepare
python native/jcef/build_runtime.py build --target windows-amd64
python native/jcef/build_runtime.py package --target windows-amd64
python native/jcef/build_runtime.py verify --target windows-amd64
```

Substitute the target of the host from the table. `prepare` checks the source SHA and
patch hash before applying the patch. Before CMake runs, `build` downloads the exact CEF
binary distribution into `third_party/cef` and checks it against the SHA-1 pinned per
target in `cefDistributions` of `source-lock.json` (the digest upstream publishes in
`https://cef-builds.spotifycdn.com/index.json`); CMake then only extracts the verified
archive. An already present archive is checked the same way. Upgrading CEF means pinning
the new four digests from that index together with `cefVersion`.
Packaging records SHA-256 for every produced artifact and native runtime file.
The Java API is compiled from the same patched source as JNI with `--release 8`.
On macOS upstream Ant also compiles its private AWT bridge, shipped in that
platform's native jar. Keep the native jar on the Java classpath. The artifact version
and jcefmaven release tag are derived from `artifactVersion` in the lock.

The native probe in [verification](verification/README.md) exercises persistence
in separate processes, profile isolation, sibling-tab ownership and clearing. Run it
on every host after `package` and keep its reports with the release inspection.
A successful build alone does not establish native behavior or replace Heartbeat's
actual Compose/native UI checks, which run locally as well:

```shell
./gradlew :features:browser:impl:browserNativeTest
./gradlew :features:browser:impl:browserComposeNativeTest
./gradlew :platform-main:di-bundle:browserRecoveryNativeTest
```

Each task runs its single test class in a fresh JVM (a CefApp cannot restart in one
process) on the JetBrains Runtime of the desktop app, after `verifyBrowserRuntime`
confirmed the locked jars. They need an interactive desktop session and are not part
of `jvmTest` or `check`.

## Artifacts and publication

Each host produces `build/jcef-artifacts` with two jars and `manifest-<target>.json`.
Copy the four outputs into one directory (for example `build/jcef-inputs/<target>/`) and
collect them; collection requires all four targets, rejects inconsistent API jars and
mismatched checksums/source versions:

```shell
python native/jcef/build_runtime.py collect --source build/jcef-inputs --output build/jcef-release
```

The release directory contains:

- `jcef-api-152.0.6-hb2.jar`
- `jcef-natives-<target>-152.0.6-hb2.jar` for each target
- `SHA256SUMS`, `runtime-manifest.json`, and completed `source-lock.json`

The native jar contains a tar.gz resource named exactly as jcefmaven expects:
`jcef-natives-<target>-<release_tag>.tar.gz`. Both API `build_meta.json` and native
metadata use the unique release tag
`jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83+heartbeat-hb2`.
This prevents reuse of an unpatched extraction cache. Native archives include
resources, locales, helper executables/apps and licenses. macOS symlinks and
executable modes are preserved. Jar entries and archive timestamps are normalized;
collection checks that all four shared API builds are byte-identical.

After all four native probes pass, `publish_draft.py` creates a draft prerelease
with the GitHub CLI; existing assets are never replaced and a retry must match the
published checksums and manifests:

```shell
python native/jcef/publish_draft.py --artifacts build/jcef-release --repository aequicor/Heartbeat --commit <sha> --version 152.0.6-hb2
```

Publication follows inspection of the verified five jars and manifests as immutable
assets of the GitHub release tag `152.0.6-hb2`. Copy the completed source lock into
this directory, and pin actual jar hashes in Gradle verification metadata.
Coordinates are `io.aequicor.heartbeat.jcef:<artifact>:152.0.6-hb2`;
Gradle consumes release assets using an Ivy artifact pattern. JOGL/gluegen Java
API dependencies remain explicit dependencies of the application.

Never substitute upstream JNI for this Java API. Never populate missing hashes
with placeholders. Until all four native builds are collected, empty
`nativeArtifacts` means publication and the native release gate are incomplete.
