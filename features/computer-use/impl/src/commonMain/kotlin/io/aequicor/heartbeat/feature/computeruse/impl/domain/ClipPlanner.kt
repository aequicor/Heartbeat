package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion

/** One kept frame of a recording and how much it differed from the last frame kept when it was offered. */
internal data class Keyframe<T>(val frame: T, val offsetMillis: Long, val changedFraction: Double)

/**
 * Decides which recorded frames reach the agent and when a recording may stop, without touching pixels.
 *
 * The first frame is the baseline; a later frame is kept when it differs from the last kept one. At most
 * [maxFrames] are kept: the baseline and the latest frame always stay, and the interior frame with the smallest
 * change gives way first. All changes between consecutive frames, and from the last kept frame, are united into
 * [changedArea], which the recorder frames the clip to: a slow drift below the threshold per frame still counts once
 * it adds up. A sliver change that repeats in the same place (a blinking caret, a ticking digit) is noise after its
 * first appearance. The screen has settled once nothing changed for [quietMillis] after the last change, or for
 * [idleMillis] when nothing changed at all. Both changes of one offer must be in the same pixels.
 */
internal class ClipPlanner<T>(
    private val maxFrames: Int,
    private val quietMillis: Long,
    private val idleMillis: Long,
) {
    init {
        require(maxFrames >= 2) { "A clip keeps the baseline and the final frame: $maxFrames" }
        require(quietMillis > 0 && idleMillis > 0) { "Settle times must be positive: $quietMillis, $idleMillis" }
    }

    private val kept = mutableListOf<Keyframe<T>>()
    private val slivers = mutableSetOf<CaptureRegion>()
    private var lastChangeMillis: Long? = null

    /** The union of every change between consecutive frames, in the frames' pixels. */
    var changedArea: CaptureRegion? = null
        private set

    val keyframes: List<Keyframe<T>> get() = kept.toList()

    /**
     * Offers the frame recorded at [offsetMillis]: [sincePrevious] compares it with the frame recorded just before,
     * [sinceKept] with the last kept frame. Returns the frame that left the clip, if any.
     */
    fun offer(frame: T, offsetMillis: Long, sincePrevious: MotionChange, sinceKept: MotionChange): T? {
        val isBaseline = kept.isEmpty()
        val changes = listOf(sincePrevious, sinceKept).map { if (it.isRepeatedSliver()) Unchanged else it }
        slivers += listOf(sincePrevious, sinceKept).filter { it.isSliver }.mapNotNull { it.bounds }
        changes.filter { it.isChanged && !isBaseline }.forEach { change ->
            lastChangeMillis = offsetMillis
            changedArea = changedArea.union(change.bounds)
        }
        val keptChange = changes.last()
        return when {
            isBaseline -> {
                kept += Keyframe(frame, offsetMillis, 0.0)
                null
            }

            !keptChange.isChanged -> frame

            else -> {
                kept += Keyframe(frame, offsetMillis, keptChange.changedFraction)
                trim()
            }
        }
    }

    private fun MotionChange.isRepeatedSliver(): Boolean = isSliver && bounds in slivers

    /** `true` once the screen stopped changing long enough after [offsetMillis] of recording. */
    fun isSettled(offsetMillis: Long): Boolean {
        val last = lastChangeMillis ?: return offsetMillis >= idleMillis
        return offsetMillis - last >= quietMillis
    }

    /** `true` when any frame differed from its predecessor. */
    val hasChanged: Boolean get() = lastChangeMillis != null

    private fun trim(): T? {
        if (kept.size <= maxFrames) return null
        val interior = (1 until kept.lastIndex).minBy { kept[it].changedFraction }
        return kept.removeAt(interior).frame
    }

    private companion object {
        val Unchanged = MotionChange(null, 0.0)
    }
}
