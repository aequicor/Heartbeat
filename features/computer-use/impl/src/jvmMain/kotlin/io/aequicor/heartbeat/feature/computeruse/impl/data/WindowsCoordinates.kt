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
    val display = displays.maxByOrNull { candidate ->
        val right = candidate.x + (candidate.widthPx * candidate.scale).roundToInt()
        val bottom = candidate.y + (candidate.heightPx * candidate.scale).roundToInt()
        (minOf(right, native.right) - maxOf(candidate.x, native.x)).coerceAtLeast(0).toLong() *
            (minOf(bottom, native.bottom) - maxOf(candidate.y, native.y)).coerceAtLeast(0)
    } ?: return null
    val startsInside = native.x >= display.x && native.y >= display.y
    val endsInside = native.right <= display.x + display.widthPx * display.scale &&
        native.bottom <= display.y + display.heightPx * display.scale
    if (requiresSingleDisplay && (!startsInside || !endsInside)) return null
    return ScreenBounds(
        display.x + ((native.x - display.x) / display.scale).roundToInt(),
        display.y + ((native.y - display.y) / display.scale).roundToInt(),
        (native.widthPx / display.scale).roundToInt().coerceAtLeast(1),
        (native.heightPx / display.scale).roundToInt().coerceAtLeast(1),
        display.scale,
    )
}
