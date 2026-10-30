package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage

/** Immutable worker-to-UI snapshot; animation never invents download progress or delays readiness. */
internal data class LauncherSceneStatus(
    val progress: ProvisionProgress = ProvisionProgress(ProvisionStage.MANIFEST),
    val isOpening: Boolean = false,
    val error: String? = null,
) {
    val isDownloadCancellable: Boolean get() = !isOpening && error == null &&
        (progress.stage == ProvisionStage.DOWNLOAD || progress.stage == ProvisionStage.MANIFEST)

    val fraction: Double? get() = progress.totalBytes.takeIf { it > 0 }?.let {
        (progress.downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0)
    }
}

/** Continuous scene clock, owned by the UI thread. Cached launches skip the first-install welcome. */
internal class LauncherMotion(private val isFirstLaunch: Boolean, private val isMotionReduced: Boolean, start: Long) {
    private val startedAt = start
    private var openingAt: Long? = null
    private var lastTick = start
    private var tipElapsed = 0L
    private var tipChangedAt = start - TIP_TRANSITION_MS
    private var pendingTip: Int? = null
    private var tipLeavingAt = start
    var tipIndex = 0
        private set

    fun tick(now: Long, status: LauncherSceneStatus, isTipPaused: Boolean) {
        val delta = (now - lastTick).coerceIn(0, MAX_TICK_MS)
        lastTick = now
        if (status.isOpening && openingAt == null) openingAt = now
        if (!status.isOpening) openingAt = null
        if (pendingTip != null && now - tipLeavingAt >= TIP_OUT_MS) {
            tipIndex = checkNotNull(pendingTip)
            pendingTip = null
            tipChangedAt = now
        }
        val isDownload = !status.isOpening && status.progress.stage == ProvisionStage.DOWNLOAD
        val isVisible = isDownload && frame(now, status).download == 1.0
        if (!isTipPaused && !isMotionReduced && isVisible) {
            tipElapsed += delta
            if (tipElapsed >= TIP_INTERVAL_MS) changeTip(1, now)
        }
    }

    fun changeTip(direction: Int, now: Long) {
        val next = (tipIndex + direction + TIP_COUNT) % TIP_COUNT
        if (isMotionReduced) {
            tipIndex = next
        } else if (pendingTip == null) {
            pendingTip = next
            tipLeavingAt = now
        }
        tipElapsed = 0
    }

    fun frame(now: Long, status: LauncherSceneStatus): LauncherMotionFrame {
        val elapsed = (now - startedAt).toDouble()
        val opening = openingFraction(now, status)
        val isIntroSkipped = isMotionReduced || !isFirstLaunch || status.error != null
        val intro = if (isIntroSkipped) IntroFrame.skipped else IntroFrame.at(elapsed)
        return LauncherMotionFrame(
            welcome = intro.welcome * (1 - opening),
            name = intro.name * (1 - opening),
            shell = intro.shell * (1 - opening),
            pulse = intro.pulse * (1 - opening),
            pulseDraw = intro.pulseDraw,
            spread = intro.reveal,
            download = intro.reveal * (1 - opening),
            opening = opening,
            shimmer = if (isMotionReduced) -1.0 else ((now - startedAt) % SHIMMER_MS).toDouble() / SHIMMER_MS,
            tipAlpha = tipOpacity(now),
        )
    }

    private fun openingFraction(now: Long, status: LauncherSceneStatus): Double = when {
        !status.isOpening -> 0.0
        isMotionReduced || !isFirstLaunch -> 1.0
        else -> phase((now - (openingAt ?: now)).toDouble(), 0.0, OPENING_FADE_MS)
    }

    private fun tipOpacity(now: Long): Double = when {
        isMotionReduced -> 1.0
        pendingTip != null -> 1 - phase((now - tipLeavingAt).toDouble(), 0.0, TIP_OUT_MS.toDouble())
        else -> phase((now - tipChangedAt).toDouble(), 0.0, TIP_TRANSITION_MS.toDouble())
    }

    companion object {
        const val TIP_COUNT = 3
        private const val TIP_INTERVAL_MS = 7_000L
        private const val TIP_TRANSITION_MS = 420L
        private const val TIP_OUT_MS = 220L
        private const val SHIMMER_MS = 1_800L
        private const val MAX_TICK_MS = 100L
        private const val OPENING_FADE_MS = 400.0
    }
}

/** Millisecond keyframes copied from the approved continuous-intro storyboard. */
private data class IntroFrame(
    val welcome: Double,
    val name: Double,
    val shell: Double,
    val pulse: Double,
    val pulseDraw: Double,
    val reveal: Double,
) {
    companion object {
        val skipped = IntroFrame(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

        // These literals are authored animation keyframes, kept together like the source storyboard timeline.
        @Suppress("MagicNumber")
        fun at(elapsed: Double) = IntroFrame(
            welcome = phase(elapsed, 80.0, 1_000.0) * (1 - phase(elapsed, 1_350.0, 1_750.0)),
            name = phase(elapsed, 1_750.0, 2_250.0) * (1 - phase(elapsed, 2_750.0, 3_150.0)),
            shell = phase(elapsed, 3_300.0, 4_200.0) * (1 - phase(elapsed, 4_750.0, 5_600.0)),
            pulse = phase(elapsed, 3_000.0, 3_450.0),
            pulseDraw = phase(elapsed, 3_050.0, 4_000.0),
            reveal = phase(elapsed, 5_600.0, 6_400.0),
        )
    }
}

// Quintic smoothstep has zero first and second derivatives at both ends of every transition.
@Suppress("MagicNumber")
private fun phase(value: Double, from: Double, to: Double): Double {
    val x = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6 - 15) + 10)
}

internal data class LauncherMotionFrame(
    val welcome: Double,
    val name: Double,
    val shell: Double,
    val pulse: Double,
    val pulseDraw: Double,
    val spread: Double,
    val download: Double,
    val opening: Double,
    val shimmer: Double,
    val tipAlpha: Double,
)

/** Decimal megabytes match the bootstrap manifest and never imply an unknown total. */
internal fun downloadMegabytes(bytes: Long): String {
    val tenths = bytes.coerceAtLeast(0) / BYTES_PER_MB_TENTH
    return "${tenths / DECIMAL_BASE},${tenths % DECIMAL_BASE}"
}

private const val BYTES_PER_MB_TENTH = 100_000
private const val DECIMAL_BASE = 10
