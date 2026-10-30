package io.aequicor.heartbeat.platform.nativelauncher

/**
 * Native-to-JVM presentation.properties, written before hidden. Coordinates are logical desktop pixels (Dp on JVM).
 * The JVM keeps jpackage.app-path and its own worker identity; this file only controls first-window presentation.
 */
internal data class HandoffPresentation(
    val isActivationRequested: Boolean,
    val isMinimized: Boolean,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val isMaximized: Boolean = false,
) {
    fun properties(): String = "activate=$isActivationRequested\nminimized=$isMinimized\n" +
        "x=$x\ny=$y\nwidth=$width\nheight=$height\nmaximized=$isMaximized\n"
}
