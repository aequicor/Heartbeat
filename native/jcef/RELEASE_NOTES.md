Patched JCEF 152.0.6 for Heartbeat: persistent request-context settings, shared
profile ownership, cookie flush and asynchronous disposal completion.
The hb2 revision also fixes macOS visibility and resize/close races accessing
destroyed NSView pointers: tasks now retain the browser and validate its view on
CEF UI. Cancelled reparent operations release their retained browser callback.
Content-view destruction detaches children before removing its final owner,
avoiding access to an already-deallocated view.
The Java DevTools client preserves responses that arrive before their method ID
and completes pending operations when closed, preventing lost-response timeouts.

The five matching Java/native jars cover Windows and macOS on x64 and ARM64.
`source-lock.json` identifies the exact upstream JCEF commit, CEF version, patch
SHA-256 and every artifact SHA-256. `SHA256SUMS` and `runtime-manifest.json` record
the collected build outputs. Reproduction commands and packaging layout are in
`native/jcef/README.md` at the release's target commit.

Publication requires successful native probes, run locally on each of the four
architectures: cross-process cookies/localStorage/IndexedDB persistence, sibling-tab
ownership, profile isolation, clear/delete, visibility/close stress and screenshot/input
checks. The maintainer keeps the probe reports with the release inspection. These
standalone native tests do not replace Heartbeat Compose UI tests.

This release is created as a draft for inspection. Existing assets are never
overwritten; retries require matching checksums and source manifests.
