Patched JCEF 152.0.6 for Heartbeat: persistent request-context settings, shared
profile ownership, cookie flush and asynchronous disposal completion.

The five matching Java/native jars cover Windows and macOS on x64 and ARM64.
`source-lock.json` identifies the exact upstream JCEF commit, CEF version, patch
SHA-256 and every artifact SHA-256. `SHA256SUMS` and `runtime-manifest.json` record
the collected build outputs. Reproduction commands and packaging layout are in
`native/jcef/README.md` at the release's target commit.

The publishing job requires successful native probes on all four architectures:
cross-process cookies/localStorage/IndexedDB persistence, sibling-tab ownership,
profile isolation, clear/delete and screenshot/input checks. CI retains the probe
reports. These standalone native tests do not replace Heartbeat Compose UI tests.

This release is created as a draft for inspection. Existing assets are never
overwritten; retries require matching checksums and source manifests.
