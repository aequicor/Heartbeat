package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteeringRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.JsonObject

/** Original input is bound to a user item once, rather than reused for every user message of a steered turn. */
internal class CodexInputHistory {
    private val originalInputs = mutableMapOf<TurnId, List<ContentPart>>()
    private val pendingInputs = mutableMapOf<TurnId, MutableList<Pair<String?, List<ContentPart>>>>()
    private val itemInputs = mutableMapOf<ItemId, List<ContentPart>>()

    fun rememberOriginals(turn: TurnId, parts: List<ContentPart>) {
        if (parts.any { it is ContentPart.Image || it is ContentPart.Resource }) originalInputs[turn] = parts.toList()
        pendingInputs[turn] = mutableListOf(null to parts.toList())
    }

    fun originals(turn: TurnId): List<ContentPart>? = originalInputs[turn]

    fun rememberSteering(turn: TurnId, request: SteeringRequest) {
        pendingInputs.getOrPut(turn) { mutableListOf() }.add(request.id.value to request.parts.toList())
    }

    fun forgetSteering(turn: TurnId, request: SteeringRequest) {
        pendingInputs[turn]?.removeAll { it.first == request.id.value }
    }

    fun itemOriginals(id: String): List<ContentPart>? = itemInputs[ItemId(id)]

    fun restoreItem(id: String, parts: List<ContentPart>) {
        itemInputs[ItemId(id)] = parts
    }

    fun parts(native: JsonObject, info: ItemInfo): List<ContentPart> {
        itemInputs[info.id]?.let { return it }
        val pending = pendingInputs[info.turn]?.removeFirstOrNull()?.second
        val parts = pending ?: native.array("content").mapNotNull { part ->
            val value = part as? JsonObject ?: protocolFailure()
            value.text("text")?.let { ContentPart.Text(it) }
        }
        itemInputs[info.id] = parts
        return parts
    }
}
