package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewMirror
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.predict

/** A panel intent numbered for the app, which applies each [seq] once and in order. */
internal data class PendingIntent(val seq: Long, val intent: AiStudioScreenIntent)

/**
 * The studio screen of a remote view: the app's frames plus the panel's intents the app has not shown yet. An
 * intent stays predicted on top of the app's screen until a frame acknowledges it, so the screen never steps back
 * to an older draft. Intents are sent once; after a failed send or another snapshot of the same instance,
 * unacknowledged intents are sent again. A new generation drops the old queue and retains its screen for recovery.
 */
internal class StudioViewSync {
    private var mirror = StudioViewMirror()
    private var confirmed: AiStudioScreenState? = null
    private var ack = 0L
    private var sentUpTo = 0L
    private var nextSeq = 0L
    private var addedInputs = 0L
    private var isReady = false

    /** The instance that may receive the queued intents, learned only from its snapshot. */
    var generation: String? = null
        private set

    /** Drafts from a lost instance, kept for manual recovery without replaying any actions. */
    var interruptedScreen: AiStudioScreenState? = null
        private set
    private val pending = mutableListOf<PendingIntent>()

    /** Local inputs acknowledged by a frame or discarded with a lost generation. */
    val settledInputs: Long get() = addedInputs - pending.size

    /** The screen to draw, `null` until the first snapshot. */
    val screen: AiStudioScreenState?
        get() = confirmed?.let { app -> pending.fold(app) { state, next -> state.predict(next.intent) } }

    /**
     * Applies an app frame.
     * @throws io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewGapException when the frame does
     * not continue the mirrored revision; the view must be watched again.
     */
    @HighFrequency
    fun receive(frame: StudioViewFrame) {
        val previousScreen = screen
        val next = mirror.apply(frame)
        if (frame is StudioViewFrame.Snapshot) {
            if (generation != null && generation != frame.generation) {
                interruptedScreen = previousScreen
                pending.clear()
                nextSeq = frame.ack
            }
            generation = frame.generation
            isReady = true
        }
        confirmed = next
        nextSeq = maxOf(nextSeq, frame.ack)
        ack = frame.ack
        pending.removeAll { it.seq <= frame.ack }
        if (frame is StudioViewFrame.Snapshot) sentUpTo = frame.ack
    }

    /** The view is watched again: the next frame must be a snapshot. */
    fun restart() {
        mirror = StudioViewMirror()
        isReady = false
    }

    /** Numbers [intent] and predicts it. */
    @HighFrequency
    fun add(intent: AiStudioScreenIntent) {
        addedInputs++
        pending += PendingIntent(++nextSeq, intent)
    }

    /** At most [limit] intents not sent yet, oldest first. */
    @HighFrequency
    fun unsent(limit: Int): List<PendingIntent> =
        if (isReady) pending.asSequence().filter { it.seq > sentUpTo }.take(limit).toList() else emptyList()

    /** The app received the intents up to [seq]. */
    fun sent(seq: Long) {
        sentUpTo = maxOf(sentUpTo, seq)
    }

    /** A send failed: whatever the app has not acknowledged goes again. */
    fun resend() {
        sentUpTo = ack
    }
}
