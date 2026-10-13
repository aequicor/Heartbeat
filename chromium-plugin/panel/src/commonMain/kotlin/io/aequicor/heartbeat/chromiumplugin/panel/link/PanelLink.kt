package io.aequicor.heartbeat.chromiumplugin.panel.link

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Calls and watches of the panel over [host]. Replies complete their calls; every other message reaches the single
 * collector of [messages] in order, including those that arrived before it started collecting.
 */
internal class PanelLink(private val host: PanelHost, private val callTimeout: Duration = CALL_TIMEOUT) {
    private val inbound = Channel<PanelInbound>(Channel.UNLIMITED)
    private val pending = mutableMapOf<Long, CompletableDeferred<PanelInbound.Reply>>()
    private var nextCall = 0L

    /** Status, agent, watch events and watch failures, in arrival order. */
    val messages: Flow<PanelInbound> = inbound.receiveAsFlow()

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
            send(PanelOutbound.Call(id, method, params))
            val answer = withTimeoutOrNull(callTimeout) { reply.await() }
                ?: throw PanelCallException(BridgeErrorCode.TIMEOUT)
            answer.error?.let { throw PanelCallException(it.code) }
            return answer.result ?: JsonNull
        } finally {
            pending.remove(id)
        }
    }

    /** Opens the app watch [key] (replacing an earlier one under the same key). */
    fun watch(key: String, method: String, params: JsonObject = JsonObject(emptyMap())) {
        send(PanelOutbound.Watch(key, method, params))
    }

    /** Closes the app watch [key]. */
    fun unwatch(key: String) {
        send(PanelOutbound.Unwatch(key))
    }

    /** Sends a message to the service worker. */
    fun send(message: PanelOutbound) {
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
        if (message is PanelInbound.Reply) {
            pending[message.id]?.complete(message) ?: log.v { "reply ${message.id} without a waiting call" }
        } else {
            inbound.trySend(message)
        }
    }

    private companion object {
        val log = Log.tag("Panel/Link")

        /** The hub waits 30 s for the app (`CALL_TIMEOUT_MS`); the panel allows the relay a little more. */
        val CALL_TIMEOUT: Duration = 35.seconds
    }
}

/** A refused or unanswered call; [code] is one of `BridgeErrorCode`. */
internal class PanelCallException(val code: String) : Exception("Panel call failed: $code")
