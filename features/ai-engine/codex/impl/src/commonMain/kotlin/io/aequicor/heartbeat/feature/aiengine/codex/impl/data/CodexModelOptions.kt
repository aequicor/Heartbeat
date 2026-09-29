package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve advertised identifiers, including future effort values unknown to Heartbeat. */
internal fun JsonObject.reasoningEfforts(): List<String> = (get("supportedReasoningEfforts") as? JsonArray).orEmpty()
    .asSequence()
    .mapNotNull { (it as? JsonObject)?.text("reasoningEffort")?.takeIf(String::isNotBlank) }
    .distinct()
    .toList()

/** Omitting effort preserves native defaults; a selected effort is sent unchanged. */
internal fun codexTurnParams(threadId: String, model: String, input: JsonArray, effort: String?): JsonObject =
    buildJsonObject {
        put("threadId", threadId)
        put("model", model)
        put("input", input)
        effort?.let { put("effort", it) }
    }
