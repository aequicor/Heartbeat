package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanProposal
import io.aequicor.heartbeat.feature.planningmode.api.PlanningTools
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private val log = Log.tag("StudioPlanCalls")

/**
 * The plan proposal behind a native tool call named [name], or null for any other tool. Streaming or unreadable
 * arguments still mark the call, so the card fills in with a later revision of the call instead of raw JSON.
 */
internal fun planProposal(name: String, arguments: String): StudioPlanProposal? {
    if (hostedToolName(name) != PlanningTools.PROPOSE) return null
    val values = parse(arguments)
    val revision = (values?.get("revision") as? JsonPrimitive)?.longOrNull
    val summary = (values?.get("summary") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return StudioPlanProposal(revision, summary)
}

private fun parse(arguments: String): JsonObject? {
    val text = arguments.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return null
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        // The parser message quotes the input, which is the agent's text: only the failure kind is kept.
        log.w(IllegalArgumentException("Invalid plan proposal arguments (${e::class.simpleName.orEmpty()})")) {
            "Plan proposal card falls back to its revision"
        }
        null
    }
}
