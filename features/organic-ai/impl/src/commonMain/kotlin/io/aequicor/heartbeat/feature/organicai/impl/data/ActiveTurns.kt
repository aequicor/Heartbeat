package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

private val log = Log.tag("ActiveTurns")

/** Unsupported and temporarily blocked capabilities are domain failures, never silent fallbacks. */
internal fun <F : EngineFeature> FeatureAccess<F>.orThrow(): F = when (this) {
    is FeatureAccess.Available -> feature
    is FeatureAccess.Unavailable -> throw EngineException(reason)
    FeatureAccess.Unsupported -> throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
}

/** The turn the engine runs on this handle, if any. */
internal fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}

/**
 * Submits [text] as [request]. [trust] goes only to a session that applies trust levels. When submission fails
 * after the engine already took the request, the turn it started is returned instead of the failure.
 */
internal suspend fun ActiveSession.submit(request: RequestId, text: String, trust: TrustLevel?): TurnId {
    val trusted = trust?.takeIf { features.resolve(AppliesTrustLevels) is FeatureAccess.Available }
    val prompt = PromptRequest(request, listOf(ContentPart.Text(text)), trust = trusted)
    return try {
        features.resolve(SendsPrompts).orThrow().send(prompt)
    } catch (e: EngineException) {
        log.w(e) { "submission of ${request.value} failed; checking native acceptance" }
        state.value.activeTurn()?.takeIf { it.request == request }?.id ?: throw e
    }
}

/**
 * Waits for [turn] to end, reporting the permission requests of that turn whenever they change. An unavailable
 * session is synchronized once; if it still knows no outcome, the outcome is unknown. A closed handle ends the wait.
 */
internal suspend fun ActiveSession.awaitTurn(
    turn: TurnId,
    onPending: suspend (List<PermissionRequest>) -> Unit,
): TurnOutcome {
    var pending = emptyList<PermissionRequest>()
    var isSynchronized = false
    var outcome: TurnOutcome? = null
    state.first { current ->
        val awaiting = (current as? ActiveSessionState.AwaitingUserAction)
            ?.takeIf { it.turn.id == turn }
            ?.requests
            .orEmpty()
        if (awaiting != pending) {
            pending = awaiting
            onPending(awaiting)
        }
        outcome = current.endOf(turn)
        if (outcome == null && current is ActiveSessionState.Unavailable && !isSynchronized) {
            // Synchronization may leave the state as it was, which emits nothing new: read it again here.
            isSynchronized = true
            synchronize()
            outcome = state.value.endOf(turn) ?: state.value.lostTurn()
        }
        outcome != null
    }
    if (pending.isNotEmpty()) onPending(emptyList())
    return outcome ?: TurnOutcome.Unknown
}

/** Cancels the running turn, waits up to [wait] for it to stop and closes the handle. */
internal suspend fun ActiveSession.stop(wait: Duration) {
    val turn = state.value.activeTurn()
    if (turn != null) {
        cancelQuietly(turn.id)
        withTimeoutOrNull(wait) { state.first { it.activeTurn() == null } }
            ?: log.w { "turn on ${ref.engine.value} did not stop in time; closing the handle" }
    }
    withContext(NonCancellable) { close() }
}

/** Best-effort cancellation; a session that cannot cancel is logged. */
internal suspend fun ActiveSession.cancelQuietly(turn: TurnId) {
    try {
        features.resolve(CancelsTurns).orThrow().cancel(turn)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "turn on ${ref.engine.value} could not be cancelled" }
    }
}

private fun ActiveSessionState.endOf(turn: TurnId): TurnOutcome? = when (this) {
    is ActiveSessionState.Ready -> lastTurn?.takeIf { it.id == turn }?.outcome

    is ActiveSessionState.Unavailable -> lastTurn?.takeIf { activeTurn == null && it.id == turn }?.outcome

    is ActiveSessionState.Closing, ActiveSessionState.Closed ->
        TurnOutcome.Failed(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))

    is ActiveSessionState.Submitting, is ActiveSessionState.Running, is ActiveSessionState.AwaitingUserAction,
    is ActiveSessionState.Interrupting,
    -> null
}

/** An unavailable session that runs nothing anymore but cannot say how the turn ended. */
private fun ActiveSessionState.lostTurn(): TurnOutcome? =
    if (this is ActiveSessionState.Unavailable && activeTurn == null) TurnOutcome.Unknown else null

private suspend fun ActiveSession.synchronize() {
    val reconciles = features.resolve(ReconcilesSession) as? FeatureAccess.Available ?: return
    try {
        reconciles.feature.synchronize()
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e) { "session on ${ref.engine.value} could not be synchronized" }
    }
}
