package io.aequicor.heartbeat.platform.desktop

/** Shared monotonic timeline. The quiet logo frame is held until the native cover has been removed. */
internal object EmbeddedIntroTimeline {
    const val JOIN_MS = 4_200L
    const val TRANSFORM_MS = 1_200L
    const val REVEAL_MS = 600L

    fun nativeElapsed(now: Long, started: Long, welcome: Boolean): Long =
        if (welcome) (now - started).coerceIn(0, JOIN_MS) else JOIN_MS

    fun continuation(now: Long, revealedAt: Long?): EmbeddedIntroFrame {
        val elapsed = revealedAt?.let { (now - it).coerceAtLeast(0) } ?: 0
        val transform = smooth(elapsed.toDouble() / TRANSFORM_MS)
        val reveal = smooth((elapsed - TRANSFORM_MS).toDouble() / REVEAL_MS)
        return EmbeddedIntroFrame(transform, 1 - reveal)
    }

    // Quintic smoothstep polynomial: zero first and second derivatives at both endpoints.
    @Suppress("MagicNumber")
    private fun smooth(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }
}

internal data class EmbeddedIntroFrame(val transform: Double, val opacity: Double)
