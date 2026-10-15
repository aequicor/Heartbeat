# Patched JCEF runtime

Heartbeat builds Java-CEF `17e805ad387d89839fdd2b864466b6510b2a783c` with CEF
`152.0.6+g708dc14+chromium-152.0.7977.83`. `source-lock.json` pins the source and
SHA-256 of `request-context-settings.patch`. Upstream files retain their BSD license.

The **152.0.6-hb5** runtime is pinned by this source lock and by Gradle
verification metadata. It contains the matching Java API and native libraries
for all five desktop targets. The previous hb1, hb2, hb3 and hb4 releases remain immutable.

hb3 fixes upstream `GetCefValueFromJNIMap`, which built a dictionary but returned an
empty, null-typed `CefValue`: `Map` (and nested `List`) values of
`CefRequestContext.setPreference` never reached CEF, so dictionary preferences such as
`proxy` were rejected. Unsupported or null entries now become explicit nulls. Heartbeat
routes the WebRTC traffic of isolated documents to a closed local proxy with it.

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

## Linux and lifecycle fixes in hb4

The **152.0.6-hb4** release adds Linux x64. Its CEF archive digest is pinned in `source-lock.json`.
The Linux patch synchronizes creation of JCEF's temporary X11 parent before Chromium uses its window ID
on a separate connection, preventing the first embedded browser from losing its native window and close callback.
Native browser-handle reads now retain the Java-owned CEF wrapper under the same lock that publishes or clears
the handle. Close releases the detached wrapper after unlocking, preventing Swing events racing with native
close from retaining a freed wrapper without running destructors under the reference lock. This applies to
all platforms, including the DevTools parent lookup. The close callback uses the identifier captured at creation;
the regression probe concurrently reads browser handles until that callback to exercise ownership.
In hb4, Linux reparent operations shared ownership of their lock and wait condition between the EDT and the
queued CEF callback. If the bounded EDT wait timed out, the callback kept that state alive until it returned,
preventing `SetParentSync` from accessing a destroyed stack condition during visibility and resize work.
The hb5 change below replaces that queued reparent operation.
Linux viewport updates keep the full browser size and apply an X11 bounding shape to the visible rectangle.
This clips drawing and input without shrinking Chromium's DOM viewport; `libXext` supplies the shape API.
Linux windowed browsers still detach during a queued forced close: Swing's `removeNotify` must move the
Chromium child to the temporary parent before AWT destroys its Canvas. Reattaching a closing browser remains
forbidden. The close/remove regression closes inactive and active tabs together, removes the active peer in
the same EDT turn, and requires both native callbacks, client cleanup, context flush, and runtime termination.
The native suite requires all 13 phases, including `close-remove` and visual scales native/1/1.5/2, to set
`fullSuitePassed`; focused diagnostics and compile-only runs do not replace the five-host release gate.

The embedder and probe also pass `--no-first-run` to prevent Chrome's standalone first-run dialog from
blocking initialization. The completed lock contains six real jar hashes, verified against all five native
builds and probes before enabling Gradle's `jcef-native` version and `jcef-linux-x64` coordinate together.
Do not publish the application with a missing Linux native jar or replace it with an unpatched upstream runtime.

## Linux teardown fix in hb5

The **152.0.6-hb5** release fixes a Linux teardown race confirmed with the CEF UI thread
deliberately held in a close callback. In hb4, the one-second wait for queued reparenting can expire while
Swing destroys its Canvas. Chromium then loses its child X11 window before processing its close message,
and never delivers the native close callback. Increasing the wait cannot guarantee safety, and waiting
indefinitely while Swing owns its tree lock can deadlock a CEF callback that needs that lock.

hb5 reparents Linux windows synchronously on the caller using a separate, checked XCB connection to the
same display. The request's server acknowledgement completes before Swing can destroy its Canvas;
neither attachment nor detachment waits for the CEF UI queue, so an older queued attachment cannot undo
a completed detach. A mutex keeps the temporary parent and display name alive through the request.
The existing creation-time XSync makes that parent visible to the new connection. XCB reports errors
without replacing the process-wide Xlib error handler shared with AWT/CEF: an already destroyed browser
window needs no reparenting; other request and connection failures are logged. The parent-change callback
retains the browser and runs later on CEF UI only while it is still valid.

The Linux library now links `libxcb`, already a dependency of its X11 runtime. Building needs `libxcb1-dev`
(also supplied by Ubuntu's `libx11-dev` dependency). The hb4 release tag and assets remain immutable.

The hb5 builds passed all 13 lifecycle-probe phases on all five native hosts in
[the runtime build](https://github.com/aequicor/Heartbeat/actions/runs/38607229980). The completed source lock
and Gradle verification metadata pin the six jars collected from those builds.

## Preparing another release

`next/` holds a prepared release while the application still uses the current one: `next/request-context-settings.patch`
is its complete patch and `next/source-lock.json` its lock (the patch's SHA-256, `artifactVersion`, no artifacts yet).
Build every target from it — in CI (below) or locally with `--patch native/jcef/next/request-context-settings.patch
--source-lock native/jcef/next/source-lock.json` added to every `build_runtime.py` command — then collect and publish
as below. Activation replaces `request-context-settings.patch` and `source-lock.json` with the complete patch
and completed lock, sets `jcef-native` in `gradle/libs.versions.toml` and pins all six jars in
`gradle/verification-metadata.xml`. Remove `next/` after committing activation; cleanup may be a separate
commit to keep the complete patch deletion within the commit-size budget.

## Build in CI

`.github/workflows/jcef-runtime.yml` builds all five targets on native GitHub runners (Windows x64 and ARM64,
macOS Intel and Apple silicon, Linux x64 on Ubuntu 24.04) with the same Python 3.13 and Microsoft JDK 21.0.10,
runs the lifecycle probe on each
host and collects the release directory into the `jcef-release` artifact. It builds `next/` when it exists, otherwise
the current lock. It never runs on its own and never publishes: add the label `jcef-runtime` to a pull request (remove
it by hand afterwards), or once the workflow is in `master` run `gh workflow run jcef-runtime.yml --ref <branch>`.
The probe's result is reported per host and its reports are uploaded. Linux must pass before collection; existing
macOS/Windows probes remain advisory. The maintainer inspects every report before running `publish_draft.py`
on the downloaded release.

## Build and verify locally

Each release can also be built, probed and collected locally, by the maintainer, on its locked native hosts.
Cross-compilation is not used:

| Target | Host | Toolchain |
| --- | --- | --- |
| Windows x64 | Windows x64 | MSVC native x64, Microsoft JDK 21 x64 |
| Windows ARM64 | Windows ARM64 | MSVC native ARM64, Microsoft JDK 21 ARM64 |
| macOS x64 | macOS on Intel | Xcode command-line tools, Microsoft JDK 21 x64 |
| macOS ARM64 | macOS on Apple silicon | Xcode command-line tools, Microsoft JDK 21 ARM64 |
| Linux x64 | Ubuntu 24.04 x64, X11/XWayland | GCC, X11/GTK development libraries, Microsoft JDK 21 x64 |

Install Python 3.13, Git, JDK 21.0.10, CMake, Ninja and the native C++ SDK. macOS also
requires Ant. Windows ARM64 uses the installed native Visual Studio toolchain (select it
with `vswhere`); it does not link against an x64 JDK. Run Windows commands from the
matching MSVC Developer Command Prompt. Use a fresh source directory for each build:

Linux needs `build-essential`, `libx11-dev`, `libxext-dev`, `libxrender-dev`, `libxtst-dev`, `libgtk-3-dev`,
`libnss3`, `libgbm1`, `libasound2t64`, `xvfb` and `xauth`. Run its lifecycle probe under
`xvfb-run --auto-servernum` on a runner without a display. Its normalized native archive includes `libjcef.so`,
`libcef.so`, CEF GPU libraries/data/locales and the executable `jcef_helper`, with original executable modes.
Browser settings point to this extracted bundle; the application does not create resource links in the system JDK.

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
archive. An already present archive is checked the same way, and an earlier extracted tree
is removed so CMake always extracts afresh (an interrupted extraction is never reused). Upgrading CEF means pinning
the new digests for every locked target from that index together with `cefVersion`.
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
Copy the outputs into one directory (for example `build/jcef-inputs/<target>/`) and
collect them; collection requires every target in the selected lock, rejects inconsistent API jars and
mismatched checksums/source versions:

```shell
python native/jcef/build_runtime.py collect --source build/jcef-inputs --output build/jcef-release
```

The release directory contains:

- `jcef-api-152.0.6-hb5.jar`
- `jcef-natives-<target>-152.0.6-hb5.jar` for each target
- `SHA256SUMS`, `runtime-manifest.json`, and completed `source-lock.json`

The native jar contains a tar.gz resource named exactly as jcefmaven expects:
`jcef-natives-<target>-<release_tag>.tar.gz`. Both API `build_meta.json` and native
metadata use the unique release tag
`jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83+heartbeat-hb5`.
This prevents reuse of an unpatched extraction cache. Native archives include
resources, locales, helper executables/apps and licenses. macOS symlinks and
executable modes are preserved. Jar entries and archive timestamps are normalized;
collection checks that all shared API builds are byte-identical.

After every native probe passes, `publish_draft.py` creates a draft prerelease
with the GitHub CLI; existing assets are never replaced and a retry must match the
published checksums and manifests:

```shell
python native/jcef/publish_draft.py --artifacts build/jcef-release --repository aequicor/Heartbeat --commit <sha> --version 152.0.6-hb5
```

Publication follows inspection of the verified jars and manifests as immutable
assets of the GitHub release tag `152.0.6-hb5`. After inspecting the draft, the maintainer publishes it
with `gh release edit 152.0.6-hb5 --repo aequicor/Heartbeat --draft=false --prerelease --latest=false`.
Gradle cannot resolve the normal release-download URLs while the release is still a draft. Copy the completed
source lock into this directory, and pin actual jar hashes in Gradle verification metadata.
Coordinates are `io.aequicor.heartbeat.jcef:<artifact>:152.0.6-hb5`;
Gradle consumes release assets using an Ivy artifact pattern. JOGL/gluegen Java
API dependencies remain explicit dependencies of the application.

Never substitute upstream JNI for this Java API. Never populate missing hashes
with placeholders. Until all locked native builds are collected, empty
`nativeArtifacts` means publication and the native release gate are incomplete.
