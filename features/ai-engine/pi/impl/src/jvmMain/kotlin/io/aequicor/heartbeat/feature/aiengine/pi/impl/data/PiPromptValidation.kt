package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason

/** Checks thinking support; attachment formats and limits are validated by [PiPromptResources]. */
internal fun piValidatePromptRequest(request: PromptRequest) {
    val effort = request.reasoningEffort
    if (effort != null && effort !in PiAcceptedThinkingLevels) {
        piFailure(EngineFailure.Request(RequestFailureReason.Invalid, request.id))
    }
}
