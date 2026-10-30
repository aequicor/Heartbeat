# Windows embedded-host experiment

Explicit opt-in JAWT API 9 / JNI Invocation host. It is **not** the installed application's entry point and does not replace the regular signed launcher / selector / rollback flow.

The outer HWND owns the taskbar icon, edge-to-edge caption, move/resize and tray. Its close button hides the window; the tray's “Выйти” hides immediately, then disposes Compose, services and the JVM. `ComposePanel` lives in the JAWT embedded Frame, with a separate `JRootPane` for dialogs; no second application JFrame is created.

## Matching-frame handoff

`HEARTBEAT_EMBEDDED_INTRO_PREVIEW=1` plays the approved Welcome → Heartbeat → logo sequence. Native holds the quiet logo at 4200 ms. Without this preview flag it starts directly at the matching logo frame. Both renderers use the same `LauncherBrand` vector source, layout, pigments and `EmbeddedIntroTimeline` contract.

Studio mounts behind an opaque Compose overlay. Native waits for Studio readiness, asks the EDT to paint and flush presentation, removes its cover, then acknowledges removal. Only this acknowledgement starts Compose's logo-to-pulse transformation and reveal. A late JVM holds the logo; timers never substitute for readiness. No synthetic download progress is displayed by this local-image probe. The production native downloader retains real byte/percentage progress and cancellation.

## Reproduce

Run `build.ps1` with `-JdkHome`, `-LlvmHome` and `-MingwHome` pointing to the JBR 21 headers and the LLVM/MinGW toolchains provisioned by Kotlin/Native. It writes only `build/embedded-host`. The init script explicitly adds experimental sources; ordinary application builds and packaging do not include them.

Then `run.ps1 -RuntimeHome <runtime> -AppDirectory <image/app> -Theme dark -Welcome` creates a fresh isolated profile and JVM options from the current test runtime classpath. Use `-Theme light` for the other palette. Omit `-Welcome` for the cached-start matching frame. It launches a visible application; diagnostic files stay under `build/embedded-host/sessions`.

Run the resulting executable with three quoted arguments: the provisioned runtime directory, a UTF-8 file with one JVM option per line, and `heartbeat_embedded_renderer.dll`. The options need the desktop test runtime classpath (including current foundation/contracts and bundle providers), `-Dheartbeat.embedded.content=studio`, `-Dheartbeat.embedded.dark=true|false`, and a **unique** `-Dheartbeat.data.name`. Set APPDATA and LOCALAPPDATA to isolated test directories. Never point this experiment at an installed user's profile. Runtime resources and Skiko paths must come from the same provisioned image. Startup failures are written beside the options file as `.native.log`.

Targeted regression: `:platform-main:desktop:test --tests '*EmbeddedIntroTimelineTest' --init-script platform-main/desktop/experimental/embedded-host/probe.gradle --no-configuration-cache`.

Experimental lint only: `:platform-main:desktop:detektTest '-Pheartbeat.embeddedLintOnly=true'` with the same init script. The property scopes this explicitly requested lint run to the experiment; normal project lint configuration is unchanged.

## Limits before production integration

Windows x64 only. Needs complete multi-monitor mixed-DPI, IME, native JCEF/popup, failure-cleanup, update/restart and single-instance lifecycle acceptance. The isolated host deliberately does not bypass or replace the production selector. macOS/Linux have not been exercised by this experiment. A matching frame does not promise pixel-identical text rasterization across GDI and Skia; the handoff anchor itself uses shared vector geometry.
