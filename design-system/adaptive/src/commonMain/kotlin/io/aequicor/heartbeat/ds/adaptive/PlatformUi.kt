package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.runtime.staticCompositionLocalOf

/** Native UI family; desktop kits are resolved only by the JVM implementation. */
enum class PlatformUi {
    Material,
    Fluent,
    MacOs,
}

/** UI family used by every adaptive control within the current theme. */
val LocalPlatformUi = staticCompositionLocalOf { defaultPlatformUi() }

/** UI kits packaged on this platform, for honest in-app previews and settings. */
expect fun supportedPlatformUis(): List<PlatformUi>

internal expect fun defaultPlatformUi(): PlatformUi

internal expect fun platformKit(platformUi: PlatformUi): PlatformKit
