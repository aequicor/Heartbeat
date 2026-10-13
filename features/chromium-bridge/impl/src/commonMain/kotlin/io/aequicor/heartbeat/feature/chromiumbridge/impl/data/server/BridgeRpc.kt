package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Framed transport of one connection; implemented over the socket on desktop and by fakes in tests. */
internal interface FrameTransport {
    /** Next payload, or `null` once the peer closed the connection. */
    suspend fun receive(): ByteArray?

    /** Sends one payload; concurrent senders are serialized by the transport. */
    suspend fun send(payload: ByteArray)
}

/** The extension on the other end, known after a successful `hello`. */
internal data class BridgeClient(
    val origin: String,
    val extensionVersion: String,
    val browserBrand: String,
    val browserVersion: String,
    val capabilities: Set<String>,
)

/** What request handlers see of a connection. */
internal interface BridgeSession {
    /** Connection id, unique within the app run, for logs and watch bookkeeping. */
    val id: Long

    /** Extension origin from the host's first frame. */
    val origin: String

    /** The handshaken client, `null` before `hello` succeeded. */
    val client: StateFlow<BridgeClient?>

    /** Events from the extension (requests and responses are routed elsewhere). */
    val events: SharedFlow<BridgeIncomingEvent>

    /** Lives as long as the connection; watches launch here and end with it. */
    val scope: CoroutineScope

    /** Records a successful handshake. */
    fun accept(client: BridgeClient)

    /** Sends an event to the extension; large events are chunked transparently. */
    suspend fun emit(event: String, params: JsonObject)

    /**
     * Calls the extension and waits for its result.
     * @throws BridgeException with the extension's error, [BridgeErrorCode.TIMEOUT] or [BridgeErrorCode.UNAVAILABLE].
     */
    suspend fun call(method: String, params: JsonObject, timeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS): JsonElement

    companion object {
        /** Default wait for an extension answer. */
        const val DEFAULT_CALL_TIMEOUT_MILLIS: Long = 30_000
    }
}

/** An event sent by the extension. */
internal data class BridgeIncomingEvent(val event: String, val params: JsonObject)

/** A request from the extension routed to a [BridgeRequestHandler]. */
internal data class BridgeCall(val method: String, val params: JsonObject, val session: BridgeSession)

/** The answer to an extension request. */
internal sealed interface BridgeReply {
    /** Success with [result]. */
    data class Ok(val result: JsonElement) : BridgeReply

    /** A refusal with one of [BridgeErrorCode]; [message] is shown to the user, so it carries no private data. */
    data class Refused(val code: String, val message: String) : BridgeReply
}

/**
 * Handles a set of extension methods. Handlers are collected into the bridge's internal multibinding, so the wire
 * protocol never leaves the feature. Expected refusals are [BridgeReply.Refused]; invalid parameters
 * (`IllegalArgumentException`) become `bad_request` and anything else `internal`.
 */
internal interface BridgeRequestHandler {
    /** Methods this handler owns; two handlers claiming one method is a configuration error. */
    val methods: Set<String>

    /** Whether the methods may be called before `hello` (only the handshake itself). */
    val isHandshake: Boolean get() = false

    /** Answers [call]. */
    suspend fun handle(call: BridgeCall): BridgeReply
}

/** A failed call to the extension with one of [BridgeErrorCode]; the message carries no private data. */
internal class BridgeException(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)
