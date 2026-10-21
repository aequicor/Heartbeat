package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val log = Log.tag("PiStoredTranscript")

/** Stored conversation [messages], oldest first; [isComplete] is false when the branch lost its beginning. */
internal data class PiStoredBranch(
    val messages: List<JsonObject>,
    val isComplete: Boolean,
    /** Last assistant after the latest context replacement; older measurements describe a different context. */
    val contextMessage: JsonObject? = null,
)

/**
 * Active branch of the native session this connection sits on. Read from `get_entries`: `get_messages` holds
 * only the model context, which after a compaction starts at its summary.
 */
internal suspend fun PiConnection.storedConversation(): PiStoredBranch = storedBranch(command("get_entries"))

/**
 * Messages of the `get_entries` response [stored] on the branch that ends at its `leafId`, oldest first.
 * Only `message` entries are the conversation: compaction and branch summaries are context Pi builds for the model,
 * extension `custom_message` entries are not shown by the live history either.
 * A branch cut at a missing parent keeps its known tail, as Pi itself does, and is incomplete.
 */
internal fun storedBranch(stored: JsonObject): PiStoredBranch {
    val entries = (stored["entries"] as? JsonArray ?: storedViolation()).mapNotNull { it as? JsonObject }
    val byId = entries.associateBy { it.string("id") }
    val leafId = ((stored["leafId"] ?: storedViolation()) as? JsonPrimitive)?.contentOrNull
    if (leafId == null) {
        // Pi reports no leaf only for an empty session.
        if (entries.isNotEmpty()) storedViolation()
        return PiStoredBranch(emptyList(), isComplete = true)
    }
    val leaf = byId[leafId] ?: storedViolation()
    val branch = generateSequence(leaf) { entry -> entry.string("parentId")?.let(byId::get) }
        .take(byId.size)
        .toList()
        .asReversed()
    val isComplete = branch.first().string("parentId") == null
    if (!isComplete) {
        log.w { "Pi stored transcript is cut at a missing entry: ${branch.size} of ${entries.size} entries kept" }
    }
    return PiStoredBranch(
        branch.filter { it.string("type") == "message" }.mapNotNull { it["message"] as? JsonObject },
        isComplete,
        storedContextMessage(branch),
    )
}

/**
 * Pi 0.87.1 appends model_change even when set_model selects the same model during reattachment.
 * Only a different route invalidates its last measurement; switching away and back cannot revive it.
 * Compaction, branch summaries and context edits replace the measured context.
 */
private fun storedContextMessage(branch: List<JsonObject>): JsonObject? {
    var message: JsonObject? = null
    for (entry in branch) {
        when (entry.string("type")) {
            "compaction", "branch_summary", "context_edit" -> message = null

            "model_change" -> if (!entry.matchesContextModel(message)) message = null

            "message" -> (entry["message"] as? JsonObject)?.takeIf { it.string("role") == "assistant" }
                ?.let { message = it }
        }
    }
    return message
}

private fun JsonObject.matchesContextModel(message: JsonObject?): Boolean {
    val provider = string("provider") ?: return false
    val id = string("modelId") ?: return false
    return provider == message?.string("provider") && id == message.string("model")
}

private fun storedViolation(): Nothing = piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
