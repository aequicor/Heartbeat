package io.aequicor.heartbeat.chromiumplugin.panel.link

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Calls and watches of the panel over [host]. The latest link status and agent control are kept, replies complete
 * their calls and watch messages reach the collector of their watch. The page has one thread: every member is used
 * on it.
 */
internal class PanelLink(private val host: PanelHost, private val callTimeout: Duration = CALL_TIMEOUT) {
    private val statuses = MutableStateFlow(PanelInbound.Status(CONNECTING))
    private val agents = MutableStateFlow(PanelInbound.Agent())
    private val pending = mutableMapOf<Long, CompletableDeferred<PanelInbound.Reply>>()
    private val watchers = mutableMapOf<String, (PanelInbound.ForWatch) -> Unit>()
    private var nextCall = 0L

    /** Changes synchronously on every loss of readiness, before queued watch events or reducers run. */
    var connectionEpoch: Long = 0
        private set

    /** Link status of the extension and the app's bridge features. */
    val status: StateFlow<PanelInbound.Status> = statuses.asStateFlow()

    /** Agent control in this browser. */
    val agent: StateFlow<PanelInbound.Agent> = agents.asStateFlow()

    init {
        host.listen(::receive)
    }

    /**
     * Calls the app method [method] and returns its result.
     * @throws PanelCallException with one of `BridgeErrorCode` when the app refuses or does not answer in time.
     */
    suspend fun call(method: String, params: JsonElement): JsonElement {
        val id = ++nextCall
        val reply = CompletableDeferred<PanelInbound.Reply>()
        pending[id] = reply
        try {
            post(PanelOutbound.Call(id, method, params))
            val answer = withTimeoutOrNull(callTimeout) { reply.await() }
                ?: throw PanelCallException(BridgeErrorCode.TIMEOUT)
            answer.error?.let { throw PanelCallException(it.code) }
            return answer.result ?: JsonNull
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Opens the app watch [key] with [method] while collected: its events and failures, in order. The service worker
     * opens it again after every reconnection. One collector per key at a time.
     */
    fun watch(key: String, method: String, params: JsonObject): Flow<PanelInbound.ForWatch> = callbackFlow {
        check(key !in watchers) { "watch $key is already collected" }
        watchers[key] = { message -> trySend(message) }
        post(PanelOutbound.Watch(key, method, params))
        awaitClose {
            watchers -= key
            post(PanelOutbound.Unwatch(key))
        }
    }.buffer(Channel.UNLIMITED)

    /** Sends a message to the service worker. */
    fun post(message: PanelOutbound) {
        host.post(panelJson.encodeToString<PanelOutbound>(message))
    }

    @HighFrequency
    private fun receive(json: String) {
        val message = try {
            panelJson.decodeFromString<PanelInbound>(json)
        } catch (e: SerializationException) {
            log.w(e) { "message of the service worker ignored" }
            return
        } catch (e: IllegalArgumentException) {
            log.w(e) { "message of the service worker ignored" }
            return
        }
        when (message) {
            is PanelInbound.Status -> {
                if (message.status != "ready") connectionEpoch++
                watchers.forEach { (key, listener) -> listener(PanelInbound.Connection(key, message.status)) }
                statuses.value = message
            }

            is PanelInbound.Agent -> agents.value = message

            is PanelInbound.Reply -> pending[message.id]?.complete(message)
                ?: log.v { "reply ${message.id} without a waiting call" }

            is PanelInbound.Event -> message.key?.let(watchers::get)
                ?.invoke(message.copy(connectionEpoch = connectionEpoch))
                ?: log.v { "message of a closed watch" }

            is PanelInbound.ForWatch -> message.key?.let(watchers::get)?.invoke(message)
                ?: log.v { "message of a closed watch" }
        }
    }

    private companion object {
        val log = Log.tag("Panel/Link")

        /** Status before the service worker reports one. */
        const val CONNECTING = "connecting"

        /** The hub waits 30 s for the app (`CALL_TIMEOUT_MS`); the panel allows the relay a little more. */
        val CALL_TIMEOUT: Duration = 35.seconds
    }
}

/** A refused or unanswered call; [code] is one of `BridgeErrorCode`. */
internal class PanelCallException(val code: String) : Exception("Panel call failed: $code")
