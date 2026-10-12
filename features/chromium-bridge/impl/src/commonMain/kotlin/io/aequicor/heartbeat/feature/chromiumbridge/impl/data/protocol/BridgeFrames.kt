package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One message of the bridge protocol. Request ids carry the sender side: `e:` from the extension, `a:` from the
 * app, so both sides can issue requests over the same connection without collisions.
 */
@Serializable
internal sealed interface BridgeFrame {
    /** A call expecting exactly one [Response] with the same [id]. */
    @Serializable
    @SerialName("req")
    data class Request(val id: String, val method: String, val params: JsonObject = EMPTY) : BridgeFrame

    /** The answer to [Request]: either [result] or [error]. */
    @Serializable
    @SerialName("res")
    data class Response(val id: String, val result: JsonElement? = null, val error: BridgeError? = null) :
        BridgeFrame

    /** A one-way notification. */
    @Serializable
    @SerialName("evt")
    data class Event(val event: String, val params: JsonObject = EMPTY) : BridgeFrame

    /**
     * A slice of a serialized frame too large for Chrome's host message cap (app to extension only).
     * [data] is base64 of [seq]-th slice of the UTF-8 bytes; the receiver joins [count] slices of set [of].
     */
    @Serializable
    @SerialName("chunk")
    data class Chunk(val of: String, val seq: Int, val count: Int, val data: String) : BridgeFrame

    /**
     * Written by the native-messaging host itself: [state] towards the extension, [origin] (the extension that
     * started the host) as the first frame towards the app.
     */
    @Serializable
    @SerialName("host")
    data class Host(val state: HostState? = null, val origin: String? = null, val hostVersion: Int? = null) :
        BridgeFrame

    companion object {
        /** Shared empty params. */
        val EMPTY: JsonObject = JsonObject(emptyMap())
    }
}

/** Availability of the app as seen by the host. */
@Serializable
internal enum class HostState {
    /** Heartbeat is not listening; the host keeps polling. */
    @SerialName("waiting")
    Waiting,

    /** The host relays frames to the running app. */
    @SerialName("connected")
    Connected,
}

/** A failed [BridgeFrame.Request]; [code] is one of [BridgeErrorCode]. */
@Serializable
internal data class BridgeError(val code: String, val message: String = "")

/** Serializes [frame] to its UTF-8 JSON payload (without the length prefix). */
internal fun encodeFrame(frame: BridgeFrame): ByteArray =
    BridgeProtocol.json.encodeToString(BridgeFrame.serializer(), frame).encodeToByteArray()

/**
 * Parses a UTF-8 JSON payload; `null` when it is not a well-formed frame of a known kind (the sender broke the
 * protocol and callers answer `bad_request`). Parser messages may quote page content, so failures are redacted.
 */
internal fun decodeFrame(payload: ByteArray): BridgeFrame? = try {
    val text = payload.decodeToString(throwOnInvalidSequence = true)
    BridgeProtocol.json.decodeFromString(BridgeFrame.serializer(), text)
} catch (e: IllegalArgumentException) {
    frameLog.w(e.redactedFailure()) { "Malformed bridge frame bytes=${payload.size}" }
    null
} catch (e: CharacterCodingException) {
    frameLog.w(e.redactedFailure()) { "Bridge frame is not UTF-8 bytes=${payload.size}" }
    null
}

private val frameLog = Log.tag("BridgeFrames")
