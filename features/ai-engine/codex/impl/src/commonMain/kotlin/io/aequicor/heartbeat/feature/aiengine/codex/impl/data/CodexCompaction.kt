package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import kotlinx.serialization.json.JsonArray

/** App-server v2 manual compaction; its turn reports progress like any other turn. */
internal const val CODEX_COMPACT_METHOD = "thread/compact/start"

/** Text of the compaction notice for consumers that do not know [NoticeKind.ContextCompacted]. */
internal const val COMPACTED_NOTICE = "Context compacted"

/** Codex compacts with its configured prompt: per-request instructions are not part of the protocol. */
internal object CodexCompactsContext : CompactsContext {
    override val isInstructionsSupported: Boolean = false
}

/**
 * Starts the native turn of [input] on [thread]: `turn/start`, or `thread/compact/start` for a compaction.
 * Returns the native turn id from the response; a compaction answers without one, its turn/started carries it.
 */
internal suspend fun CodexRpc.startTurn(
    thread: String,
    model: String,
    input: CodexPromptInputs,
    effort: String?,
): String? {
    if (input.isCompaction) {
        request(CODEX_COMPACT_METHOD, json("threadId" to thread.json()))
        return null
    }
    val response = request("turn/start", codexTurnParams(thread, model, JsonArray(input.parts), effort))
    return response.obj("turn").text("id") ?: protocolFailure()
}

/** Rejects an effort the selected model does not advertise, before any native turn starts. */
internal suspend fun CodexRuntime.validateReasoningEffort(target: EngineTarget, request: PromptRequest) {
    val effort = request.reasoningEffort ?: return
    val model = models(target.binding).firstOrNull { it.target == target }
    if (effort !in model?.reasoningEfforts.orEmpty()) {
        fail(
            EngineFailure.Request(RequestFailureReason.Invalid, request.id),
        )
    }
}
