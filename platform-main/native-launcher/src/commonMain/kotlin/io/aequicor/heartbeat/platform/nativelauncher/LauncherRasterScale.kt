package io.aequicor.heartbeat.platform.nativelauncher

/** Bounds antialiasing overhead on 4K/8K screens; the native-size frame itself is always retained. */
internal fun launcherRasterScale(width: Int, height: Int): Int {
    val pixels = width.toLong() * height
    return listOf(HIGH_SAMPLING, 2, 1).first { scale -> scale == 1 || pixels * scale * scale <= MAX_SAMPLED_PIXELS }
}

private const val MAX_SAMPLED_PIXELS = 16L * 1024 * 1024

private const val HIGH_SAMPLING = 4
