package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelCallException
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelInbound
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.chromiumplugin.panel.link.panelJson
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewGapException
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewLimitException
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewReader
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewSchema
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

private val log = Log.tag("Panel/Studio")

/**
 * The studio screen of the app drawn in the panel: watches the remote view [viewId] (`studio.watch`), mirrors its
 * frames and sends the user's screen intents (`studio.intent`) in numbered batches. The draft, its caret and the
 * sidebar change at once; everything else waits for the app's frame.
 */
internal fun remoteStudioStore(
    factory: HeartbeatStoreFactory,
    link: PanelLink,
    viewId: String,
    retryDelay: Duration = RETRY_DELAY,
): Store<RemoteStudioState, StudioIntent, RemoteStudioAction> =
    factory.create("ChromiumStudio", RemoteStudioState(), onError = { this }) {
        val view = StudioViewLink(link, viewId, retryDelay)
        init {
            launch { view.follow { change -> updateState { change() } } }
            launch { view.deliver() }
        }
        reduce { intent ->
            val screen = view.add(intent)
            updateState { copy(screen = screen) }
        }
    }

/** A state change of the store. */
private typealias StateChange = RemoteStudioState.() -> RemoteStudioState

/** What ended one watch of the view. */
private enum class WatchEnd { Again, Failed, Final }

/** The panel's link to one remote view. Used on the page's single thread. */
private class StudioViewLink(
    private val link: PanelLink,
    private val viewId: String,
    private val retryDelay: Duration,
) {
    private val sync = StudioViewSync()
    private val reader = StudioViewReader()
    private var snapshotEpoch: Long? = null

    // Wakes the sender; conflated, since every wake-up sends all that is unsent.
    private val outbox = Channel<Unit>(Channel.CONFLATED)

    // Asks the watcher to watch again: the app lost the view or the mirror lost track.
    private val restarts = Channel<Unit>(Channel.CONFLATED)

    private val params = JsonObject(
        mapOf("view" to JsonPrimitive(viewId), "schema" to JsonPrimitive(StudioViewSchema.fingerprint)),
    )

    /** Queues [intent] for the app and returns the screen with it predicted. */
    @HighFrequency
    fun add(intent: StudioIntent): AiStudioScreenState? {
        sync.add(intent)
        outbox.trySend(Unit)
        return sync.screen
    }

    /**
     * Watches the view for the store's lifetime: again at once after a lost view, after [retryDelay] after a
     * refusal, never again for another schema.
     */
    suspend fun follow(update: suspend (StateChange) -> Unit) {
        while (true) {
            update { copy(status = StudioViewStatus.Connecting) }
            sync.restart()
            reader.reset()
            val end = merge(link.watch(WATCH_KEY, STUDIO_WATCH, params), restarts.receiveAsFlow().map { null })
                .mapNotNull { message ->
                    when (message) {
                        null -> WatchEnd.Again

                        is PanelInbound.Connection -> {
                            if (message.status != LinkStatus.Ready.wire) {
                                sync.restart()
                                reader.reset()
                                update { copy(status = StudioViewStatus.Connecting) }
                            }
                            null
                        }

                        is PanelInbound.WatchFailed -> refused(message, update)

                        is PanelInbound.Event -> receive(message, update)
                    }
                }
                .first()
            // Another schema stays another schema until the extension or the app is updated.
            if (end == WatchEnd.Final) return
            if (end == WatchEnd.Failed) delay(retryDelay)
        }
    }

    /** Sends queued intents for the store's lifetime. */
    suspend fun deliver() {
        outbox.receiveAsFlow().collect {
            while (sendBatch()) continue
        }
    }

    private suspend fun refused(failure: PanelInbound.WatchFailed, update: suspend (StateChange) -> Unit): WatchEnd {
        val code = failure.error?.code
        val status = code.viewStatus()
        log.w { "studio view refused: ${code ?: "no code"}" }
        update { copy(status = status) }
        return if (status == StudioViewStatus.Incompatible) WatchEnd.Final else WatchEnd.Failed
    }

    /** Applies a frame event; [WatchEnd.Again] when the mirror lost track. */
    @HighFrequency
    private suspend fun receive(event: PanelInbound.Event, update: suspend (StateChange) -> Unit): WatchEnd? {
        if (link.status.value.status != LinkStatus.Ready.wire) return null
        if (event.event != STUDIO_CHANGED || (event.params["view"] as? JsonPrimitive)?.content != viewId) return null
        val frame = try {
            reader.read(requireNotNull(event.params["frame"] as? JsonObject))
        } catch (e: StudioViewLimitException) {
            log.w(e) { "studio view too large; awaiting a smaller snapshot" }
            sync.restart()
            update { copy(status = StudioViewStatus.TooLarge) }
            return null
        } catch (e: SerializationException) {
            log.w(e) { "studio frame unreadable, watching again" }
            return WatchEnd.Again
        } catch (e: IllegalArgumentException) {
            log.w(e) { "studio frame unreadable, watching again" }
            return WatchEnd.Again
        }
        if (frame == null) return null
        try {
            sync.receive(frame)
        } catch (e: StudioViewGapException) {
            log.w(e) { "studio frames lost, watching again" }
            return WatchEnd.Again
        }
        if (frame is StudioViewFrame.Snapshot) snapshotEpoch = event.connectionEpoch
        val screen = sync.screen
        update {
            copy(
                status = StudioViewStatus.Live,
                screen = screen,
                generation = sync.generation,
                settledInputs = sync.settledInputs,
                connectionEpoch = snapshotEpoch,
                interruptedScreen = sync.interruptedScreen,
            )
        }
        if (frame is StudioViewFrame.Snapshot) outbox.trySend(Unit)
        return null
    }

    /** Sends one batch of unsent intents; `false` when nothing is left or the send failed. */
    private suspend fun sendBatch(): Boolean {
        if (link.status.value.status != LinkStatus.Ready.wire || snapshotEpoch != link.connectionEpoch) return false
        val batch = sync.unsent(MAX_BATCH).nextStudioBatch()
        if (batch.isEmpty()) return false
        val generation = checkNotNull(sync.generation)
        val call = studioBatchCall(viewId, generation, batch)
        return try {
            val reply = panelJson.decodeFromJsonElement(ViewAck.serializer(), link.call(call.method, call.params))
            if (reply.refused.isNotEmpty()) log.i { "${reply.refused.size} studio intents refused by the app" }
            if (sync.generation == generation) sync.sent(batch.last().seq)
            true
        } catch (e: PanelCallException) {
            log.w(e) { "studio intents not delivered" }
            retry(generation, immediately = e.code == BridgeErrorCode.UNKNOWN_VIEW)
            false
        } catch (e: SerializationException) {
            log.w(e) { "studio intent reply unreadable" }
            retry(generation)
            false
        } catch (e: IllegalArgumentException) {
            log.w(e) { "studio intent reply unreadable" }
            retry(generation)
            false
        }
    }

    private suspend fun retry(generation: String, immediately: Boolean = false) {
        if (sync.generation == generation) {
            sync.restart()
            sync.resend()
            if (!immediately) delay(retryDelay)
            // A snapshot may have replaced the instance while the sender waited.
            if (sync.generation == generation) restarts.trySend(Unit)
        }
    }
}

private fun String?.viewStatus(): StudioViewStatus = when (this) {
    BridgeErrorCode.UNSUPPORTED_PROTOCOL -> StudioViewStatus.Incompatible

    BridgeErrorCode.BUSY -> StudioViewStatus.Busy

    BridgeErrorCode.UNAVAILABLE -> StudioViewStatus.Unavailable

    // Timeouts and lost links: the service worker reports the link itself, the view retries.
    else -> StudioViewStatus.Connecting
}

/** `studio.intent` result: the last applied sequence number and the refused ones. */
@Serializable
private data class ViewAck(val ack: Long, val refused: List<Long> = emptyList())

private const val WATCH_KEY = "studio"
private const val STUDIO_WATCH = "studio.watch"
private const val STUDIO_CHANGED = "studio.changed"

/** The app takes at most 64 intents per call (`SidePanelStudioHandlers`). */
private const val MAX_BATCH = 64

/** A refused or failed view is watched again after this delay. */
private val RETRY_DELAY: Duration = 5.seconds
