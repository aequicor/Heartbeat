package io.aequicor.heartbeat.platform.desktop

/** Configures both desktop entry points before Compose creates its first native surface. */
internal fun configureDesktopRendering() {
    // Heavyweight Chromium requires owned windows for dialogs/popups above its surface.
    System.setProperty("compose.layers.type", "WINDOW")
    desktopRenderer(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
        ?.let { System.setProperty("skiko.renderApi", it) }
}

/**
 * Skiko 0.150.1 `SkiaLayer.awt.kt:reshape` redraws Direct3D synchronously during resize.
 * WINDOW layers resize inside their drawing callback, reentering the same PictureRecorder.
 * `SkikoProperties.fallbackRenderApiQueue` drops backends preceding the requested backend:
 * OpenGL falls only to software. Windows ARM64 rejects OpenGL, so starts with software.
 * macOS keeps its Metal default. An explicit SKIKO_RENDER_API environment override takes precedence.
 */
internal fun desktopRenderer(osName: String, architecture: String): String? = when {
    !osName.startsWith("Windows", ignoreCase = true) -> null
    architecture.lowercase() in listOf("aarch64", "arm64") -> "SOFTWARE_FAST"
    else -> "OPENGL"
}
