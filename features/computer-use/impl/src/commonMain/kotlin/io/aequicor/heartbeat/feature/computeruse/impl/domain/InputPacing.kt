package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import kotlin.math.abs

/** Vertical wheel deltas are counted in notches of this many pixels. */
internal const val WHEEL_NOTCH_PX = 40

/** Upper bound of wheel notches per action; a scroll larger than this is clamped instead of timing out. */
internal const val MAX_WHEEL_NOTCHES = 40

/** Clicks of one click action; a larger count is clamped. */
internal const val MAX_CLICKS = 3

/**
 * UTF-16 code units of one typed text (a character outside the BMP counts as two), for the hosted tools and the
 * engine router alike.
 * Bounds how long one call holds the input device, about a minute at worst on the slow key-code path, so long text
 * arrives in steps the agent can verify.
 */
internal const val MAX_TYPED_CHARS = 1_000

/**
 * The notch count of a scroll delta, rounded half away from zero and clamped to [MAX_WHEEL_NOTCHES]. Negative
 * deltas scroll up.
 */
internal fun wheelNotches(deltaY: Int): Int {
    // Clamp before the arithmetic: abs(Int.MIN_VALUE) and the rounding offset would overflow and flip the direction.
    val magnitude = abs(deltaY.toLong()).coerceAtMost(MAX_WHEEL_NOTCHES.toLong() * WHEEL_NOTCH_PX)
    val notches = ((magnitude + WHEEL_NOTCH_PX / 2) / WHEEL_NOTCH_PX).toInt()
    return if (deltaY < 0) -notches else notches
}

/** Worst-case device time of one action, milliseconds; real devices are usually faster. */
internal fun inputExecutionMillis(action: InputAction): Long = when (action) {
    is InputAction.MoveTo -> MOVE_MILLIS
    is InputAction.Click -> CLICK_MILLIS * action.count.coerceIn(1, MAX_CLICKS)
    is InputAction.Drag -> DRAG_MILLIS
    is InputAction.Scroll -> SCROLL_NOTCH_MILLIS * abs(wheelNotches(action.deltaY)) + BASE_MILLIS
    is InputAction.Type -> TYPE_CHAR_MILLIS * action.text.length + BASE_MILLIS
    is InputAction.Key -> KEY_MILLIS * action.keys.size + BASE_MILLIS
}

/** How long a caller should wait for one action before treating the device as stuck: base plus twice the estimate. */
internal fun inputWaitLimitMillis(action: InputAction): Long = BASE_WAIT_MILLIS + 2 * inputExecutionMillis(action)

private const val BASE_MILLIS = 1_000L
private const val MOVE_MILLIS = 200L
private const val CLICK_MILLIS = 500L
private const val DRAG_MILLIS = 3_000L
private const val SCROLL_NOTCH_MILLIS = 60L
private const val TYPE_CHAR_MILLIS = 60L
private const val KEY_MILLIS = 500L

/** Base wait shared by every action; slow devices and busy systems get this much before any per-action time. */
internal const val BASE_WAIT_MILLIS = 30_000L
