package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ClipPlanner
import io.aequicor.heartbeat.feature.computeruse.impl.domain.Keyframe
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MotionChange
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MotionSignature
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.RawFrame
import io.aequicor.heartbeat.feature.computeruse.impl.domain.motionSignature
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

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

/**
 * Captures frames at the requested rate and keeps the ones that changed, inside [area] of the master. Stops at the
 * time limit, or once the screen settled when the request asks for it; `null` when a frame could not be captured.
 */
internal class ClipRecorder(private val clock: () -> Long) {
    suspend fun record(
        request: RecordRequest,
        area: (PixelGrid) -> CaptureRegion,
        capture: suspend () -> RawFrame?,
    ): RecordedFrames? {
        val planner = ClipPlanner<PixelGrid>(request.maxFrames, QUIET_MILLIS, IDLE_MILLIS)
        val interval = MILLIS_PER_SECOND / request.fps
        val start = clock()
        var previous: MotionSignature? = null
        var kept: MotionSignature? = null
        var last: RawFrame
        var scale: Double
        var elapsed: Long
        do {
            val frameStart = clock()
            last = capture() ?: return null
            elapsed = frameStart - start
            scale = workingScale(last.pixels)
            val working = last.pixels.workingCopy(scale)
            val signature = working.region(area(last.pixels).scaled(scale, working)).motionSignature()
            val sincePrevious = previous?.compare(signature) ?: NoChange
            val sinceKept = kept?.compare(signature) ?: NoChange
            val dropped = planner.offer(working, elapsed, sincePrevious.inMaster(scale), sinceKept)
            if (dropped !== working) kept = signature
            previous = signature
            val isDone = elapsed >= request.maxDurationMillis ||
                (request.isUntilStable && planner.isSettled(elapsed))
            if (!isDone) delay(max(0L, interval - (clock() - frameStart)))
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

        /** This master rectangle in the pixels of [working], kept inside it. */
        fun CaptureRegion.scaled(scale: Double, working: PixelGrid): CaptureRegion {
            val left = (x * scale).toInt().coerceIn(0, working.widthPx - 1)
            val top = (y * scale).toInt().coerceIn(0, working.heightPx - 1)
            val width = (widthPx * scale).roundToInt().coerceIn(1, working.widthPx - left)
            val height = (heightPx * scale).roundToInt().coerceIn(1, working.heightPx - top)
            return CaptureRegion(left, top, width, height)
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
