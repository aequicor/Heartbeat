package io.aequicor.heartbeat.platform.desktop

/** Configures both desktop entry points before Compose creates its first native surface. */
internal fun configureDesktopRendering() {
    desktopRenderingProperties(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
        .forEach { (name, value) -> System.setProperty(name, value) }
}

/**
 * The same properties are supplied to isolated JCEF tests by `JcefRuntimeExtension.renderingProperties`.
 * macOS blending leaves interop clearing inside Compose's clip instead of punching a rectangular hole
 * through the entire Metal canvas, so a rounded native document exposes the real backdrop at its corners.
 * Small popups retain owned WINDOW layers; the modal portal separately hides native background peers.
 */
internal fun desktopRenderingProperties(osName: String, architecture: String): Map<String, String> = buildMap {
    put("compose.layers.type", "WINDOW")
    if (osName.startsWith("Mac", ignoreCase = true)) put("compose.interop.blending", "true")
    desktopRenderer(osName, architecture)?.let { put("skiko.renderApi", it) }
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
