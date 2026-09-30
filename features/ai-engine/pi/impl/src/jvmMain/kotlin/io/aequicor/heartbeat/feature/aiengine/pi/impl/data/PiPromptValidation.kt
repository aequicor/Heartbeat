package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason

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
