package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/** AWT keeps a monitor's physical origin but expresses offsets and dimensions in user-space units. */
internal fun windowsDisplays(): List<ScreenBounds> = GraphicsEnvironment.getLocalGraphicsEnvironment()
    .screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val rectangle = configuration.bounds
        ScreenBounds(
            rectangle.x,
            rectangle.y,
            rectangle.width,
            rectangle.height,
            configuration.defaultTransform.scaleX,
        )
    }

/**
 * Inverts WRobotPeer's toDeviceSpaceAbs: scale offsets around the monitor origin, never the origin itself.
 * A client rectangle spanning different monitor coordinate maps cannot be expressed by one linear frame map.
 * Such geometry is unavailable, rather than returning plausible coordinates that click a different location.
 */
internal fun windowsUserBounds(
    native: ScreenBounds,
    displays: List<ScreenBounds>,
    requiresSingleDisplay: Boolean = false,
): ScreenBounds? {
    val display = displays.maxByOrNull { overlap(native, it) } ?: return null
    if (overlap(native, display) == 0L) return null
    // GetWindowRect includes invisible resize borders outside a maximized monitor. Off-screen margins are safe;
    // only an intersection with a second monitor introduces a different AWT coordinate transform.
    if (requiresSingleDisplay && displays.any { it != display && overlap(native, it) > 0L }) return null
    return ScreenBounds(
        display.x + ((native.x - display.x) / display.scale).roundToInt(),
        display.y + ((native.y - display.y) / display.scale).roundToInt(),
        (native.widthPx / display.scale).roundToInt().coerceAtLeast(1),
        (native.heightPx / display.scale).roundToInt().coerceAtLeast(1),
        display.scale,
    )
}

private fun overlap(native: ScreenBounds, display: ScreenBounds): Long {
    val right = display.x + (display.widthPx * display.scale).roundToInt()
    val bottom = display.y + (display.heightPx * display.scale).roundToInt()
    return (minOf(right, native.right) - maxOf(display.x, native.x)).coerceAtLeast(0).toLong() *
        (minOf(bottom, native.bottom) - maxOf(display.y, native.y)).coerceAtLeast(0)
}
