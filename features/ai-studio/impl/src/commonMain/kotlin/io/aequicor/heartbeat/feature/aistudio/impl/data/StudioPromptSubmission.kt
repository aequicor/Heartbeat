package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import kotlin.uuid.Uuid

private val log = Log.tag("StudioPromptSubmission")

internal suspend fun ActiveSession.submitStudioPrompt(
    prompt: String,
    reasoningEffort: String?,
    trust: TrustLevel?,
    attachments: List<ResourceRef>,
): TurnId {
    val request = PromptRequest(
        RequestId(Uuid.random().toString()),
        listOfNotNull(prompt.takeIf(String::isNotBlank)?.let(ContentPart::Text)) + attachments.map {
            if (it.mediaType.startsWith("image/")) ContentPart.Image(it) else ContentPart.Resource(it)
        },
        reasoningEffort = reasoningEffort,
        trust = trust,
    )
    return try {
        features.requireFeature(SendsPrompts).send(request)
    } catch (e: EngineException) {
        log.e(e) { "Submission failed; inspect native acceptance before changing run status" }
        val accepted = state.value.activeTurn()?.takeIf { it.request == request.id }
        if (accepted != null) accepted.id else throw e
    }
}

internal fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}

/** Complete transient input of a profile-owned turn; only durable resource refs enter history. */
internal data class StudioTurnSubmission(
    val prompt: String,
    val settings: RunSettings,
    val attachments: List<ResourceRef>,
    val onAccepted: suspend () -> Unit,
) {
    override fun toString(): String = "StudioTurnSubmission(attachments=${attachments.size})"
}
