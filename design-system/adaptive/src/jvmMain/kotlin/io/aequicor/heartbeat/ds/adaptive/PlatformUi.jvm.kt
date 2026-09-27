package io.aequicor.heartbeat.ds.adaptive

import java.util.Locale

/** Selects the desktop kit at runtime, preserving a single portable JVM artifact. */
fun detectDesktopPlatformUi(): PlatformUi = desktopPlatformUi(System.getProperty("os.name").orEmpty())

/** The desktop artifact packages all kits for runtime OS selection and previews. */
actual fun supportedPlatformUis(): List<PlatformUi> = PlatformUi.entries

internal fun desktopPlatformUi(osName: String): PlatformUi = when {
    osName.lowercase(Locale.ROOT).startsWith("windows") -> PlatformUi.Fluent
    osName.lowercase(Locale.ROOT).startsWith("mac") -> PlatformUi.MacOs
    else -> PlatformUi.Material
}

internal actual fun defaultPlatformUi(): PlatformUi = detectDesktopPlatformUi()

internal actual fun platformKit(platformUi: PlatformUi): PlatformKit = when (platformUi) {
    PlatformUi.Material -> MaterialKit
    PlatformUi.Fluent -> FluentKit
    PlatformUi.MacOs -> MacOsKit
}
