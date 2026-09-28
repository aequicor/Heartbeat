package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Indexed public summaries; native reasoning content and encrypted fields are deliberately not projected. */
internal class CodexReasoning {
    private val summaries = mutableMapOf<ItemId, MutableMap<Int, String>>()

    fun snapshot(id: ItemId, native: JsonObject): List<ContentPart.Reasoning> {
        summaries[id] = native.array("summary").mapIndexed { index, value ->
            index to ((value as? JsonPrimitive)?.contentOrNull ?: protocolFailure())
        }.toMap().toMutableMap()
        return parts(id)
    }

    fun append(id: ItemId, native: JsonObject): List<ContentPart.Reasoning> {
        val index = native.text("summaryIndex")?.toIntOrNull()?.takeIf { it >= 0 } ?: protocolFailure()
        val delta = native.text("delta") ?: protocolFailure()
        val blocks = summaries.getOrPut(id) { mutableMapOf() }
        blocks[index] = blocks[index].orEmpty() + delta
        return parts(id)
    }

    private fun parts(id: ItemId): List<ContentPart.Reasoning> = summaries.getValue(id).entries
        .sortedBy { it.key }.mapNotNull { (_, text) ->
            text.takeIf(String::isNotBlank)?.let { ContentPart.Reasoning(it) }
        }
}
