Patched JCEF 152.0.6 for Heartbeat: persistent request-context settings, shared
profile ownership, cookie flush and asynchronous disposal completion.
The hb2 revision also fixes macOS visibility and resize/close races accessing
destroyed NSView pointers: tasks now retain the browser and validate its view on
CEF UI. Cancelled reparent operations release their retained browser callback.
Content-view destruction detaches children before removing its final owner,
avoiding access to an already-deallocated view.
The Java DevTools client preserves responses that arrive before their method ID
and completes pending operations when closed, preventing lost-response timeouts.
The hb3 revision passes `Map` and `List` values of `CefRequestContext.setPreference`
to CEF: upstream built the dictionary but returned an empty, null-typed value, so
dictionary preferences such as `proxy` were rejected. Heartbeat uses this to send the
WebRTC traffic of its isolated documents to a closed local proxy.

The hb4 revision adds Linux x64 to the same patched Java/native contract. The six matching
Java/native jars cover Windows and macOS on x64 and ARM64, plus Linux x64. Linux requires
Ubuntu 24.04-compatible system libraries and an X11 or XWayland display.
The Linux native patch waits for creation of JCEF's temporary X11 parent before Chromium
uses it from another connection, fixing the first browser's missing native window and
close callback. Native browser handles are retained and cleared under a shared lock;
the detached wrapper is released after unlocking. This fixes a use-after-free when
Swing visibility/focus events race with native browser close, observed on Linux.
Linux reparent callbacks retain their lock and wait condition after the bounded EDT wait
times out. The queued `SetParentSync` callback can finish without accessing destroyed
stack state during visibility and resize operations.
Linux viewport clipping uses an X11 bounding shape while preserving Chromium's full
layout size. It clips both drawing and input without changing the DOM viewport.
During forced close, Linux still detaches Chromium before AWT destroys its Canvas;
closing an inactive and an active tab together now completes both native callbacks,
client cleanup, context flush and runtime termination. The release probe requires
all 13 phases, including this close/remove regression, on every native host.
Embedders must pass `--no-first-run` to suppress Chrome's standalone
first-run dialog during initialization; Heartbeat and the lifecycle probe both do so.

The hb5 revision fixes a Linux teardown race when the CEF UI thread is busy during
Swing peer removal. The previous one-second wait could expire before Chromium's
child window detached from its Canvas, so destroying the Canvas lost the native
close callback. Linux parent changes now use a synchronous, checked XCB request
on a separate connection to the same display. The X server acknowledges each
attach or detach before Swing can continue, without waiting for the CEF UI queue
while holding Swing's tree lock. Deferred callbacks retain the browser and run
on CEF UI only while it remains valid. The native library links libxcb, already
part of the X11 runtime dependencies.

The close/remove regression deliberately pauses CEF UI in another browser's
close callback, then removes the active Swing peer before releasing that pause.
It also detaches and reattaches a live browser during the pause before closing
it, checking that an earlier attachment cannot undo the final detach. Both
native close callbacks, client cleanup, context flush and runtime termination
are required. Windows and macOS keep their existing reparent implementation.

`source-lock.json` identifies the exact upstream JCEF commit, CEF version, patch
SHA-256 and every artifact SHA-256. `SHA256SUMS` and `runtime-manifest.json` record
the collected build outputs. Reproduction commands and packaging layout are in
`native/jcef/README.md` at the release's target commit.

The release is built by `.github/workflows/jcef-runtime.yml` on native GitHub runners
for all five targets with the same Python and Microsoft JDK, or locally. Publication
requires successful native probes on every target: cross-process cookies/localStorage/IndexedDB persistence, sibling-tab
ownership, profile isolation, clear/delete, visibility/close stress and screenshot/input
checks. The maintainer keeps the probe reports with the release inspection. These
standalone native tests do not replace Heartbeat Compose UI tests.

This release is created as a draft for inspection. Existing assets are never
overwritten; retries require matching checksums and source manifests.
