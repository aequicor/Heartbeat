package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseGesture
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import kotlin.math.pow

/** A point in host screen coordinates, as the agent cursor draws it. */
internal data class CursorPoint(val x: Double, val y: Double)

/** Timing of the agent cursor; reduced motion jumps and shows effects without movement. */
internal data class CursorTiming(
    val glideMillis: Int,
    val rippleMillis: Int,
    val trailMillis: Int,
    val badgeMillis: Int,
    val isReducedMotion: Boolean,
)

/** What the agent cursor draws at one instant; every offset is relative to the pointer tip. */
internal data class CursorScene(
    val tip: CursorPoint,
    /** Click rings: radius fraction `0..1` and opacity `0..1` of each ring. */
    val rings: List<CursorRing> = emptyList(),
    /** Drag trail from the oldest to the newest point, with the opacity of each point. */
    val trail: List<Pair<CursorPoint, Float>> = emptyList(),
    /** Action label and its opacity. */
    val badge: CursorBadge? = null,
    /** `true` while something still moves or fades and the next frame differs from this one. */
    val isAnimating: Boolean = false,
)

internal data class CursorRing(val progress: Float, val alpha: Float)

internal data class CursorBadge(val text: String, val alpha: Float)

/**
 * Pure animation state of the agent cursor, advanced by the overlay's frame timer. The tip eases between the
 * acknowledged pointer steps; a new effect (click, drag start, wheel, typing) starts its ring, trail or label once.
 * A drag trail stays while the button is held and fades from the moment it is released.
 *
 * [timing] can change (theme or reduced motion) without forgetting which effects were already shown.
 */
internal class AgentCursorAnimator(var timing: CursorTiming) {
    private var from: CursorPoint? = null
    private var to: CursorPoint? = null
    private var movedAtMillis = 0L
    private var effect: Effect? = null
    private var effectSequence = 0L
    private var gesture: ComputerUseGesture? = null
    private val trail = ArrayDeque<Pair<CursorPoint, Long>>()

    /** Moves the target to [point]; a new [effectSequence] starts the effect of [gesture]. */
    @HighFrequency
    fun update(point: CursorPoint, gesture: ComputerUseGesture?, effectSequence: Long, nowMillis: Long) {
        val current = to
        if (current == null) {
            from = point
            to = point
            movedAtMillis = nowMillis
        } else if (current != point) {
            from = tip(nowMillis)
            to = point
            movedAtMillis = nowMillis
        }
        if (effectSequence > this.effectSequence && gesture != null) {
            effect = Effect(gesture, nowMillis)
            trail.clear()
        }
        val isReleased = this.gesture is ComputerUseGesture.Drag && gesture !is ComputerUseGesture.Drag
        if (isReleased) {
            // The trail fades from the release, not from when each point was drawn.
            val held = trail.map { (trailPoint, _) -> trailPoint to nowMillis }
            trail.clear()
            trail.addAll(held)
        }
        this.effectSequence = effectSequence
        this.gesture = gesture
        if (gesture is ComputerUseGesture.Drag && trail.lastOrNull()?.first != point) trail.addLast(point to nowMillis)
        while (trail.size > MAX_TRAIL_POINTS) trail.removeFirst()
    }

    /**
     * Forgets the position: the next pointer appears in place instead of gliding from a stale one. Effects up to
     * [effectSequence] happened while the cursor was hidden and are never replayed.
     */
    fun reset(effectSequence: Long = this.effectSequence) {
        from = null
        to = null
        effect = null
        gesture = null
        trail.clear()
        this.effectSequence = maxOf(this.effectSequence, effectSequence)
    }

    /** Called on every frame of the overlay timer. */
    @HighFrequency
    fun scene(nowMillis: Long): CursorScene? {
        val tip = to?.let { tip(nowMillis) } ?: return null
        val rings = rings(nowMillis)
        val trailPoints = trail(nowMillis)
        val badge = badge(nowMillis)
        val isGliding = !timing.isReducedMotion && nowMillis - movedAtMillis < timing.glideMillis
        val isFading = rings.isNotEmpty() || trailPoints.isNotEmpty() || badge != null
        return CursorScene(tip, rings, trailPoints, badge, isAnimating = isGliding || isFading)
    }

    private fun tip(nowMillis: Long): CursorPoint {
        val start = from ?: return checkNotNull(to)
        val end = checkNotNull(to)
        if (timing.isReducedMotion || timing.glideMillis <= 0) return end
        val progress = ((nowMillis - movedAtMillis).toDouble() / timing.glideMillis).coerceIn(0.0, 1.0)
        val eased = 1.0 - (1.0 - progress).pow(EASE_POWER)
        return CursorPoint(start.x + (end.x - start.x) * eased, start.y + (end.y - start.y) * eased)
    }

    private fun rings(nowMillis: Long): List<CursorRing> {
        val press = effect?.takeIf { it.gesture is ComputerUseGesture.Press } ?: return emptyList()
        val count = (press.gesture as ComputerUseGesture.Press).count.coerceIn(1, MAX_RINGS)
        return (0 until count).mapNotNull { index ->
            val elapsed = nowMillis - press.startedAtMillis - index * timing.rippleMillis / RING_STAGGER
            val progress = elapsed.toFloat() / timing.rippleMillis
            when {
                elapsed < 0 -> null
                progress >= 1f -> null
                timing.isReducedMotion -> CursorRing(1f, 1f)
                else -> CursorRing(progress, 1f - progress)
            }
        }
    }

    private fun trail(nowMillis: Long): List<Pair<CursorPoint, Float>> {
        val isHeld = gesture is ComputerUseGesture.Drag
        return trail.mapNotNull { (point, at) ->
            val age = if (isHeld) 0f else (nowMillis - at).toFloat() / timing.trailMillis
            if (age >= 1f) null else point to (1f - age)
        }
    }

    private fun badge(nowMillis: Long): CursorBadge? {
        val active = effect ?: return null
        val text = active.gesture.label() ?: return null
        val progress = (nowMillis - active.startedAtMillis).toFloat() / timing.badgeMillis
        if (progress >= 1f) return null
        val fadeStart = 1f - BADGE_FADE_FRACTION
        val alpha = if (timing.isReducedMotion || progress < fadeStart) 1f else (1f - progress) / BADGE_FADE_FRACTION
        return CursorBadge(text, alpha)
    }

    private data class Effect(val gesture: ComputerUseGesture, val startedAtMillis: Long)

    private companion object {
        const val EASE_POWER = 3.0
        const val MAX_RINGS = 3
        const val RING_STAGGER = 3
        const val MAX_TRAIL_POINTS = 48
        const val BADGE_FADE_FRACTION = 0.3f
    }
}

/**
 * The label next to the cursor, made of symbols and key names so it needs no translation: never the typed text
 * itself, only what kind of input runs.
 */
internal fun ComputerUseGesture.label(): String? = when (this) {
    ComputerUseGesture.Move -> null

    is ComputerUseGesture.Press -> when (button) {
        MouseButton.Left -> null
        MouseButton.Right -> "R"
        MouseButton.Middle -> "M"
    }

    is ComputerUseGesture.Drag -> null

    is ComputerUseGesture.Scroll -> when {
        notches < 0 -> "↑ ${-notches}"
        notches > 0 -> "↓ $notches"
        else -> null
    }

    is ComputerUseGesture.Type -> "⌨ $length"

    is ComputerUseGesture.Keys -> names.joinToString("+") { name -> name.replaceFirstChar { it.uppercase() } }
}
