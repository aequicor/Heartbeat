package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One execution process, including its event reader. Object identity is the connection generation: native request
 * ids are only unique within this object. Replies and delayed tool jobs must retain the originating connection.
 * Before binding, CodexRpc's bounded event channel retains startup events without blocking RPC responses.
 */
internal class CodexConnection(
    val rpc: CodexRpc,
    private val scope: CoroutineScope,
    observe: suspend (JsonObject) -> Unit,
    failed: (CodexConnection, EngineFailure) -> Unit,
) : AutoCloseable {
    private val log = Log.tag("CodexConnection")
    private val bound = CompletableDeferred<CodexSession>()
    var isClosed = false
        private set
    private val observer = scope.launch {
        try {
            val session = bound.await()
            rpc.notifications.collect { message ->
                observe(message)
                val thread = (message["params"] as? JsonObject)?.text("threadId")
                if (thread == session.ref.nativeId) {
                    session.event(message, this@CodexConnection)
                } else {
                    message["id"]?.let { rpc.reject(it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "Codex execution connection failed" }
            if (!isClosed) failed(this@CodexConnection, e.failure)
        }
    }

    fun bind(session: CodexSession) {
        check(!isClosed && bound.complete(session)) { "Execution connection cannot be rebound" }
    }

    /** Delayed cleanup answers stay on this connection even if its session has acquired another process. */
    fun answerLater(id: JsonElement, result: JsonObject) {
        if (isClosed || !scope.isActive) return
        scope.launch { respondQuietly(id, result) }
    }

    suspend fun respondQuietly(id: JsonElement, result: JsonObject) {
        if (isClosed) return
        try {
            rpc.respond(id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Codex tool response not delivered" }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        observer.cancel()
        rpc.close()
    }
}
