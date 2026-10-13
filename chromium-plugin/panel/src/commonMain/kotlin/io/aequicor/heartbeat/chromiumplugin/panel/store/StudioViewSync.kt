package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewMirror
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.predict

/** A panel intent numbered for the app, which applies each [seq] once and in order. */
internal data class PendingIntent(val seq: Long, val intent: AiStudioScreenIntent)

/**
 * The studio screen of a remote view: the app's frames plus the panel's intents the app has not shown yet. An
 * intent stays predicted on top of the app's screen until a frame acknowledges it, so the screen never steps back
 * to an older draft. Intents are sent once; after a failed send or a snapshot (new attachment or view) everything
 * not acknowledged is sent again, which the app ignores for intents it already applied.
 */
internal class StudioViewSync {
    private var mirror = StudioViewMirror()
    private var confirmed: AiStudioScreenState? = null
    private var ack = 0L
    private var sentUpTo = 0L
    private var nextSeq = 0L
    private val pending = mutableListOf<PendingIntent>()

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
        confirmed = mirror.apply(frame)
        ack = frame.ack
        pending.removeAll { it.seq <= frame.ack }
        if (frame is StudioViewFrame.Snapshot) sentUpTo = frame.ack
    }

    /** The view is watched again: the next frame must be a snapshot. */
    fun restart() {
        mirror = StudioViewMirror()
    }

    /** Numbers [intent] and predicts it. */
    @HighFrequency
    fun add(intent: AiStudioScreenIntent) {
        pending += PendingIntent(++nextSeq, intent)
    }

    /** At most [limit] intents not sent yet, oldest first. */
    @HighFrequency
    fun unsent(limit: Int): List<PendingIntent> = pending.asSequence().filter { it.seq > sentUpTo }.take(limit).toList()

    /** The app received the intents up to [seq]. */
    fun sent(seq: Long) {
        sentUpTo = maxOf(sentUpTo, seq)
    }

    /** A send failed: whatever the app has not acknowledged goes again. */
    fun resend() {
        sentUpTo = ack
    }
}
