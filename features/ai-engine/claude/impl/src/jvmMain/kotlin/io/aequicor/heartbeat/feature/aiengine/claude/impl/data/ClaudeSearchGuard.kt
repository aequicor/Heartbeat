package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Search transport receives a captured turn, not the profile bearer's authority or a caller-supplied identity. */
internal class ClaudeSearchGuard(
    private val tools: ProfileAgentTools,
    private val context: suspend () -> AgentToolContext?,
    private val isCurrent: () -> Boolean,
) : SearchBridgeGuard {
    private val log = Log.tag("ClaudeSearchGuard")

    override suspend fun execute(
        name: String,
        arguments: JsonObject,
        operation: suspend () -> AgentToolResult,
    ): AgentToolResult = coroutineScope {
        val bound = context()?.copy(callId = ToolCallId(UUID.randomUUID().toString()))
            ?: return@coroutineScope ended()
        val invocation = checkNotNull(currentCoroutineContext()[Job])
        val revoked = bound.lifetime?.invokeOnCompletion {
            invocation.cancel(CancellationException("Search turn ended"))
        }
        try {
            if (!isCurrent() || bound.lifetime?.isActive != true) return@coroutineScope ended()
            when (val verdict = tools.authorizeHosted(bound, name, arguments)) {
                is NativeVerdict.Deny -> return@coroutineScope AgentToolResult(verdict.reason, isError = true)
                NativeVerdict.Allow -> Unit
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || bound.lifetime?.isActive != true) return@coroutineScope ended()
            val result = operation()
            val note = afterResult(bound, name, arguments, result)
            if (note.isNullOrBlank()) result else result.copy(text = note.take(MAX_NOTE_CHARS) + "\n\n" + result.text)
        } finally {
            revoked?.dispose()
        }
    }

    private suspend fun afterResult(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        result: AgentToolResult,
    ): String? = try {
        tools.afterHosted(context, name, arguments, result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Search result hook failed (${error::class.simpleName.orEmpty()})")) {
            "Search completed but its result hook failed"
        }
        null
    }

    private fun ended(): AgentToolResult = AgentToolResult("Search turn is no longer active", isError = true)
}

private const val MAX_NOTE_CHARS = 2_000
