package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.isRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val log = Log.tag("StudioPromptSubmission")

/** Native send evidence; preparation failures and proven rejections never retain outside tool restrictions. */
internal class StudioPromptDelivery {
    var isPending: Boolean = false
}

internal suspend fun ActiveSession.submitStudioPrompt(
    prompt: String,
    reasoningEffort: String?,
    trust: TrustLevel?,
    attachments: List<ResourceRef>,
    requestId: RequestId,
    compaction: ContextCompaction? = null,
    delivery: StudioPromptDelivery? = null,
    decisionMode: AgentToolDecisionMode = AgentToolDecisionMode.AutomaticSafety,
): TurnId {
    // A compaction carries no input and no effort: it only summarizes the native context. It keeps the chat's trust,
    // which the engine would otherwise reset to its default and report back as the chat's configuration.
    val request = if (compaction != null) {
        PromptRequest(
            requestId,
            emptyList(),
            trust = trust,
            compaction = supportedCompaction(compaction),
            decisionMode = decisionMode,
        )
    } else {
        PromptRequest(
            requestId,
            listOfNotNull(prompt.takeIf(String::isNotBlank)?.let(ContentPart::Text)) + attachments.map {
                if (it.mediaType.startsWith("image/")) ContentPart.Image(it) else ContentPart.Resource(it)
            },
            reasoningEffort = reasoningEffort,
            trust = trust,
            decisionMode = decisionMode,
        )
    }
    val sender = features.requireFeature(SendsPrompts)
    return try {
        delivery?.isPending = true
        sender.send(request)
    } catch (e: EngineException) {
        log.e(e) { "Submission failed; local pending identity does not prove native acceptance" }
        if (e.failure.isRejection()) {
            delivery?.isPending = false
            synchronizeSubmission()
            throw e
        }
        resolveUnknownSubmission(request.id, e, delivery)
    }
}

/** Focus instructions reach only engines that take them; others compact with their own prompt. */
private fun ActiveSession.supportedCompaction(compaction: ContextCompaction): ContextCompaction {
    val compacts = (features.resolve(CompactsContext) as? FeatureAccess.Available)?.feature
    if (compaction.instructions == null || compacts?.isInstructionsSupported != false) return compaction
    log.i { "Compaction instructions dropped: the engine compacts with its own prompt" }
    return ContextCompaction()
}

/**
 * Keeps the profile reservation until reconciliation establishes acceptance or a known idle native session.
 * A closed handle ends observation while preserving pending delivery; it never proves a rejected request.
 */
private suspend fun ActiveSession.resolveUnknownSubmission(
    request: RequestId,
    error: EngineException,
    delivery: StudioPromptDelivery?,
): TurnId {
    val initial = state.value
    val isSynchronized = synchronizeSubmission()
    val resolved = state.first { current ->
        current == ActiveSessionState.Closed || current.confirmedTurn(request) != null ||
            (current is ActiveSessionState.Ready && (isSynchronized || current != initial))
    }
    if (resolved == ActiveSessionState.Closed) throw error
    resolved.confirmedTurn(request)?.let { return it.id }
    delivery?.isPending = false
    throw error
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
