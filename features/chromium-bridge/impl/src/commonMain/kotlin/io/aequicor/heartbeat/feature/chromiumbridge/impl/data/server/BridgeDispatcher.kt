package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import kotlinx.coroutines.CancellationException

/** Routes extension requests to the handler owning the method. */
internal class BridgeDispatcher(handlers: Set<BridgeRequestHandler>) {
    private val log = Log.tag("BridgeDispatcher")
    private val routes: Map<String, BridgeRequestHandler> = buildMap {
        handlers.forEach { handler ->
            handler.methods.forEach { method ->
                val previous = put(method, handler)
                check(previous == null) { "Bridge method $method is claimed twice" }
            }
        }
    }

    /** Methods known to the app, for diagnostics. */
    val methods: Set<String> get() = routes.keys

    /** Runs the handler of [call]; refuses unknown methods and calls before the handshake. */
    suspend fun dispatch(call: BridgeCall): BridgeReply {
        val handler = routes[call.method]
            ?: return BridgeReply.Refused(BridgeErrorCode.UNKNOWN_METHOD, "Unknown method")
        if (!handler.isHandshake && call.session.client.value == null) {
            return BridgeReply.Refused(BridgeErrorCode.NOT_READY, "Handshake required")
        }
        val reply = try {
            handler.handle(call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Invalid parameters (decoding, validation) are the caller's fault; messages may quote the input.
            log.w(e.redactedFailure()) { "Bridge request rejected method=${call.method}" }
            BridgeReply.Refused(BridgeErrorCode.BAD_REQUEST, "Invalid parameters")
        } catch (e: Exception) {
            log.w(e.redactedFailure()) { "Bridge request failed method=${call.method}" }
            BridgeReply.Refused(BridgeErrorCode.INTERNAL, "Request failed")
        }
        if (reply is BridgeReply.Refused) log.i { "Bridge request refused method=${call.method} code=${reply.code}" }
        return reply
    }
}
