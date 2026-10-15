package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ClipPlanner
import io.aequicor.heartbeat.feature.computeruse.impl.domain.Keyframe
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MotionChange
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MotionSignature
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.RawFrame
import io.aequicor.heartbeat.feature.computeruse.impl.domain.motionSignature
import io.aequicor.heartbeat.feature.computeruse.impl.domain.scaledInto
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * The frames of one recording before encoding. Keyframes are kept at a bounded working resolution ([scale] of the
 * master) so a long recording of a large desktop stays small in memory; [last] is the full-resolution frame captured
 * last, and [changed] is the united change in master pixels.
 */
internal data class RecordedFrames(
    val keyframes: List<Keyframe<PixelGrid>>,
    val scale: Double,
    val last: RawFrame,
    val changed: CaptureRegion?,
    val isStable: Boolean,
    val durationMillis: Long,
)

/** Monotonic milliseconds for recording pace and duration; never the wall clock, which can jump. */
internal fun interface ClipClock {
    fun millis(): Long
}

/** The host's monotonic clock. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MonotonicClipClock : ClipClock {
    private val start = TimeSource.Monotonic.markNow()

    override fun millis(): Long = start.elapsedNow().inWholeMilliseconds
}

/**
 * Captures frames at the requested rate and keeps the ones that changed, inside [area] of the master. Stops at the
 * time limit, or once the screen settled when the request asks for it; `null` when a frame could not be captured.
 */
internal class ClipRecorder(private val clock: ClipClock) {
    /** One iteration per recorded frame; nothing here logs, the coordinator reports the finished clip once. */
    @HighFrequency
    suspend fun record(
        request: RecordRequest,
        area: (PixelGrid) -> CaptureRegion,
        capture: suspend () -> RawFrame?,
    ): RecordedFrames? {
        val planner = ClipPlanner<PixelGrid>(request.maxFrames, QUIET_MILLIS, IDLE_MILLIS)
        val interval = MILLIS_PER_SECOND / request.fps
        val start = clock.millis()
        var previous: MotionSignature? = null
        var kept: MotionSignature? = null
        var last: RawFrame
        var scale: Double
        var elapsed: Long
        do {
            val frameStart = clock.millis()
            last = capture() ?: return null
            elapsed = frameStart - start
            scale = workingScale(last.pixels)
            val working = last.pixels.workingCopy(scale)
            val signature = working.region(area(last.pixels).scaledInto(scale, working)).motionSignature()
            val sincePrevious = previous?.compare(signature) ?: NoChange
            val sinceKept = kept?.compare(signature) ?: NoChange
            val dropped = planner.offer(working, elapsed, sincePrevious.inMaster(scale), sinceKept.inMaster(scale))
            if (dropped !== working) kept = signature
            previous = signature
            val isDone = elapsed >= request.maxDurationMillis ||
                (request.isUntilStable && planner.isSettled(elapsed))
            if (!isDone) delay(max(0L, interval - (clock.millis() - frameStart)))
        } while (!isDone)
        return RecordedFrames(
            keyframes = planner.keyframes,
            scale = scale,
            last = last,
            changed = planner.changedArea?.offset(area(last.pixels)),
            isStable = planner.isSettled(elapsed),
            durationMillis = elapsed,
        )
    }

    private companion object {
        /** A change is over once nothing moved for this long. */
        const val QUIET_MILLIS = 800L

        /** Without any change, a recording that waits for the screen to settle stops after this long. */
        const val IDLE_MILLIS = 1_500L

        const val MILLIS_PER_SECOND = 1_000L

        /** Longest side of the working copy kept in memory for each keyframe. */
        const val WORKING_PX = 1920

        val NoChange = MotionChange(null, 0.0)

        fun workingScale(pixels: PixelGrid): Double =
            minOf(1.0, WORKING_PX.toDouble() / max(pixels.widthPx, pixels.heightPx))

        fun PixelGrid.workingCopy(scale: Double): PixelGrid = if (scale >= 1.0) {
            this
        } else {
            scaled(
                (widthPx * scale).roundToInt().coerceAtLeast(1),
                (heightPx * scale).roundToInt().coerceAtLeast(1),
            )
        }

        /** A change measured in working pixels of the area, mapped back to master pixels relative to the area. */
        fun MotionChange.inMaster(scale: Double): MotionChange = copy(bounds = bounds?.unscaled(scale))

        fun CaptureRegion.unscaled(scale: Double): CaptureRegion = CaptureRegion(
            (x / scale).toInt(),
            (y / scale).toInt(),
            (widthPx / scale).roundToInt().coerceAtLeast(1),
            (heightPx / scale).roundToInt().coerceAtLeast(1),
        )

        fun CaptureRegion.offset(area: CaptureRegion): CaptureRegion =
            CaptureRegion(area.x + x, area.y + y, widthPx, heightPx)
    }
}
