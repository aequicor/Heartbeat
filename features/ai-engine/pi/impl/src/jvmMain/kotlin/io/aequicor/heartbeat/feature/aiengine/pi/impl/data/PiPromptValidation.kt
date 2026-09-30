package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Checks native text and thinking support before the prompt can become an accepted turn. */
internal fun piValidatePromptRequest(request: PromptRequest) {
    if (request.parts.any { it !is ContentPart.Text }) {
        piFailure(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
    }
    val effort = request.reasoningEffort
    if (effort != null && effort !in PiAcceptedThinkingLevels) {
        piFailure(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
    }
}

/** Native model switches stay on the authenticated provider of the original target. */
internal fun piModelFields(model: ModelId, original: ModelId): JsonObject {
    val provider = model.value.substringBefore("/")
    if (provider != original.value.substringBefore("/") || "/" !in model.value) {
        piFailure(EngineFailure.Access(AccessFailureReason.ModelAccessDenied))
    }
    return JsonObject(
        mapOf("provider" to JsonPrimitive(provider), "modelId" to JsonPrimitive(model.value.substringAfter("/"))),
    )
}
