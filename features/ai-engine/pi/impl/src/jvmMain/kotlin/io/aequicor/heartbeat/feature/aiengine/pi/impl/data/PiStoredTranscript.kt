package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val log = Log.tag("PiStoredTranscript")

/**
 * Messages on the active branch of the native session this connection sits on, oldest first. Read from
 * `get_entries`: `get_messages` holds only the model context, which after a compaction starts at its summary.
 */
internal suspend fun PiConnection.storedMessages(): List<JsonObject> = storedBranch(command("get_entries"))

/**
 * Messages of the `get_entries` response [stored] on the branch that ends at its `leafId`, oldest first.
 * Only `message` entries are the conversation: compaction and branch summaries are context Pi builds for the model,
 * extension `custom_message` entries are not shown by the live history either.
 * A branch cut at a missing parent keeps its known tail, as Pi itself does.
 */
internal fun storedBranch(stored: JsonObject): List<JsonObject> {
    val entries = (stored["entries"] as? JsonArray ?: storedViolation()).mapNotNull { it as? JsonObject }
    val byId = entries.associateBy { it.string("id") }
    val leafId = ((stored["leafId"] ?: storedViolation()) as? JsonPrimitive)?.contentOrNull
    if (leafId == null) {
        // Pi reports no leaf only for an empty session.
        if (entries.isNotEmpty()) storedViolation()
        return emptyList()
    }
    val leaf = byId[leafId] ?: storedViolation()
    val branch = generateSequence(leaf) { entry -> entry.string("parentId")?.let(byId::get) }
        .take(byId.size)
        .toList()
        .asReversed()
    if (branch.first().string("parentId") != null) {
        log.w { "Pi stored transcript is cut at a missing entry: ${branch.size} of ${entries.size} entries kept" }
    }
    return branch.filter { it.string("type") == "message" }.mapNotNull { it["message"] as? JsonObject }
}

private fun storedViolation(): Nothing = piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
