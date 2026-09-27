package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AcpPermissions(private val handler: AcpClientHandler, private val scope: CoroutineScope) {
    private val log = Log.tag("AcpPermissions")
    private val lock = Mutex()
    private val pending = mutableMapOf<JsonElement, Decision>()
    private val cancelledSessions = mutableSetOf<String>()

    suspend fun receive(id: JsonElement, params: JsonObject, peer: AcpRpcPeer) {
        val request = acpJson.decodeFromJsonElement(AcpPermissionRequest.serializer(), params)
        val decision = Decision(request.sessionId)
        lock.withLock {
            check(id !in pending) { "Duplicate permission request" }
            pending[id] = decision
            if (request.sessionId in cancelledSessions) decision.cancel()
        }
        val worker = scope.launch { decide(request, decision) }
        lock.withLock {
            decision.worker = worker
            if (decision.result.isCompleted) worker.cancel()
        }
        scope.launch { respond(id, decision, peer) }
    }

    suspend fun begin(sessionId: String) {
        lock.withLock { cancelledSessions.remove(sessionId) }
    }

    suspend fun cancel(sessionId: String) {
        log.i { "ACP cancel pending permissions" }
        lock.withLock {
            cancelledSessions.add(sessionId)
            pending.values.filter { it.sessionId == sessionId }.forEach { it.cancel() }
        }
    }

    private suspend fun decide(request: AcpPermissionRequest, decision: Decision) {
        try {
            if (decision.result.isCompleted) return
            val outcome = handler.requestPermission(request)
            check(outcome !is AcpPermissionOutcome.Selected || request.options.any { it.optionId == outcome.optionId })
            decision.result.complete(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(AcpDiagnostic(e)) { "ACP permission handler failed; cancelling permission" }
        } finally {
            decision.result.complete(AcpPermissionOutcome.Cancelled)
        }
    }

    private suspend fun respond(id: JsonElement, decision: Decision, peer: AcpRpcPeer) {
        try {
            val chosen = decision.result.await()
            // Serialize the wire response with cancellation, including decisions chosen but not yet sent.
            lock.withLock {
                val outcome = if (decision.isCancelled) AcpPermissionOutcome.Cancelled else chosen
                log.d { "ACP permission decision" }
                peer.respond(id, fields("outcome" to encode(outcome)))
                pending.remove(id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(AcpDiagnostic(e)) { "ACP permission response failed" }
        } finally {
            withContext(NonCancellable) { lock.withLock { pending.remove(id) } }
        }
    }

    private fun encode(outcome: AcpPermissionOutcome): JsonObject = when (outcome) {
        AcpPermissionOutcome.Cancelled -> fields("outcome" to JsonPrimitive("cancelled"))

        is AcpPermissionOutcome.Selected -> fields(
            "outcome" to JsonPrimitive("selected"),
            "optionId" to JsonPrimitive(outcome.optionId),
        )
    }

    private class Decision(val sessionId: String) {
        val result = CompletableDeferred<AcpPermissionOutcome>()
        var worker: Job? = null
        var isCancelled = false

        fun cancel() {
            isCancelled = true
            result.complete(AcpPermissionOutcome.Cancelled)
            worker?.cancel()
        }
    }
}
