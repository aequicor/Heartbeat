package io.aequicor.heartbeat.ds.adaptive

/** iOS uses Material controls; desktop dependencies are excluded. */
actual fun supportedPlatformUis(): List<PlatformUi> = listOf(PlatformUi.Material)

internal actual fun defaultPlatformUi(): PlatformUi = PlatformUi.Material

internal actual fun platformKit(platformUi: PlatformUi): PlatformKit {
    require(platformUi == PlatformUi.Material) { "iOS supports the Material kit only." }
    return MaterialKit
}
