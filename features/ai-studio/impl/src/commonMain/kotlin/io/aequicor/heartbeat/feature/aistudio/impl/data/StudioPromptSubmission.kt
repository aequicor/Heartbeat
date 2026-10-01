package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val log = Log.tag("StudioPromptSubmission")

internal suspend fun ActiveSession.submitStudioPrompt(
    prompt: String,
    reasoningEffort: String?,
    trust: TrustLevel?,
    attachments: List<ResourceRef>,
    requestId: RequestId,
): TurnId {
    val request = PromptRequest(
        requestId,
        listOfNotNull(prompt.takeIf(String::isNotBlank)?.let(ContentPart::Text)) + attachments.map {
            if (it.mediaType.startsWith("image/")) ContentPart.Image(it) else ContentPart.Resource(it)
        },
        reasoningEffort = reasoningEffort,
        trust = trust,
    )
    return try {
        features.requireFeature(SendsPrompts).send(request)
    } catch (e: EngineException) {
        log.e(e) { "Submission failed; local pending identity does not prove native acceptance" }
        val failure = e.failure as? EngineFailure.Request
        if (failure?.reason != RequestFailureReason.OutcomeUnknown) {
            synchronizeSubmission()
            throw e
        }
        resolveUnknownSubmission(request.id, e)
    }
}

/** Keeps the profile reservation until reconciliation establishes acceptance or a known idle native session. */
private suspend fun ActiveSession.resolveUnknownSubmission(request: RequestId, error: EngineException): TurnId {
    val initial = state.value
    val isSynchronized = synchronizeSubmission()
    val resolved = state.first { current ->
        current.confirmedTurn(request) != null ||
            (current is ActiveSessionState.Ready && (isSynchronized || current != initial))
    }
    return resolved.confirmedTurn(request)?.id ?: throw error
}

/** Rechecks local failure against the native session so the same handle can accept a corrected prompt. */
private suspend fun ActiveSession.synchronizeSubmission(): Boolean {
    val reconciler = features.resolve(ReconcilesSession) as? FeatureAccess.Available
        ?: return false
    return try {
        reconciler.feature.synchronize()
        true
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        log.e(failure) { "Submission reconciliation failed; preserve the original failure and native ownership" }
        false
    }
}

/** Only native-confirmed execution/terminal phases count; Submitting and Unavailable.activeTurn may be local. */
private fun ActiveSessionState.confirmedTurn(request: RequestId): Turn? = when (this) {
    is ActiveSessionState.Running -> turn

    is ActiveSessionState.AwaitingUserAction -> turn

    is ActiveSessionState.Interrupting -> turn

    // Recheck may synthesize a terminal Unknown from a purely local pending turn.
    is ActiveSessionState.Ready -> lastTurn?.takeUnless { it.outcome == TurnOutcome.Unknown }

    is ActiveSessionState.Unavailable -> lastTurn?.takeUnless { it.outcome == TurnOutcome.Unknown }

    is ActiveSessionState.Submitting, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}?.takeIf { it.request == request }

internal fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}
