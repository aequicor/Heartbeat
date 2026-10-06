package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Declarations of the organism tools. They only file requests the organism carries out later, so the trust gate
 * treats them as reads; the actions of the work itself are gated as usual. Older threads with frozen tool names
 * can request the receive operation through the existing status tool.
 */
internal val organismToolSpecs: List<AgentToolSpec> = listOf(
    AgentToolSpec(
        OrganismTools.DIVIDE,
        "Organic AI cells only. Split off a child cell that works on a substantial, self-contained subtask in " +
            "parallel. The child does not see your conversation, so the task must say everything it needs. Its " +
            "result stays in your inbox; request it with ${OrganismTools.RECEIVE} when ready.",
        schema(required = listOf(OrganismTools.Arguments.TASK)) {
            text(OrganismTools.Arguments.TASK, "The complete subtask, up to ${OrganismBounds.MAX_TASK} characters")
            text(OrganismTools.Arguments.NAME, "A short name for the child, e.g. \"tests\"")
        },
        AgentToolAction.Read,
    ),
    AgentToolSpec(
        OrganismTools.COMPLAIN,
        "Organic AI cells only. Report a cancerous cell: one that loops without progress, sabotages, takes " +
            "destructive actions, fabricates results or works against the goal. A fresh immune session judges it " +
            "and may kill the cell with its descendants. The zygote cannot be accused.",
        schema(required = listOf(OrganismTools.Arguments.CELL, OrganismTools.Arguments.REASON)) {
            text(OrganismTools.Arguments.CELL, "Id of the accused cell, e.g. c3")
            text(OrganismTools.Arguments.REASON, "What the cell did, with concrete evidence")
        },
        AgentToolAction.Read,
    ),
    AgentToolSpec(
        OrganismTools.DISPUTE,
        "Organic AI cells only. Ask the immune system for a binding answer when cells disagree or a contested " +
            "decision blocks you. Read the answer with ${OrganismTools.RECEIVE} when ready.",
        schema(required = listOf(OrganismTools.Arguments.QUESTION)) {
            text(OrganismTools.Arguments.QUESTION, "The question with the options and arguments")
            putJsonObject(OrganismTools.Arguments.PARTIES) {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Ids of up to ${OrganismBounds.MAX_PARTIES} other cells concerned")
            }
        },
        AgentToolAction.Read,
    ),
    AgentToolSpec(
        OrganismTools.STATUS,
        "Organic AI cells only. Show the organism: its goal, every cell with its state and the open cases.",
        schema(required = emptyList()) {
            putJsonObject("receive") {
                put("type", "boolean")
                put("description", "Use the receive operation in older sessions without organism_receive")
            }
            receiveProperties()
        },
        AgentToolAction.Read,
    ),
    AgentToolSpec(
        OrganismTools.RECEIVE,
        "Organic AI cells only. Read your queued child results and immune decisions when ready. " +
            "With wait=true and no ready results, register a durable wait and end your turn only after the " +
            "tool confirms it. The next result resumes you after your current turn ends; call this tool again " +
            "to read it. Results never arrive as chat messages. Reads can be replayed with after=0.",
        schema(required = emptyList()) { receiveProperties() },
        AgentToolAction.Read,
    ),
)

private fun JsonObjectBuilder.receiveProperties() {
    putJsonObject(OrganismTools.Arguments.AFTER) {
        put("type", "integer")
        put("minimum", 0)
        put("description", "Optional cursor from a previous receive; omit for unread results, 0 to replay")
    }
    putJsonObject(OrganismTools.Arguments.WAIT) {
        put("type", "boolean")
        put("description", "If no results are ready, explicitly sleep until a result; default false")
    }
}

private fun schema(required: List<String>, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

private fun JsonObjectBuilder.text(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}
