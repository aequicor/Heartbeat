package io.aequicor.heartbeat.ds.adaptive

/** The browser (the Chromium extension side panel) uses Material controls; desktop kits are JVM-only. */
actual fun supportedPlatformUis(): List<PlatformUi> = listOf(PlatformUi.Material)

internal actual fun defaultPlatformUi(): PlatformUi = PlatformUi.Material

internal actual fun platformKit(platformUi: PlatformUi): PlatformKit {
    require(platformUi == PlatformUi.Material) { "The browser supports the Material kit only." }
    return MaterialKit
}
