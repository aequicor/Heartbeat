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
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * Submits [text] as [request]. [trust] goes only to a session that applies trust levels. Only an ambiguous delivery
 * may have started a turn: then the turn the session remembers for [request] is followed instead of the failure. A
 * definite refusal never started one, so it fails the submission, and an unavailable session is synchronized so it
 * can take the next turn.
 */
internal suspend fun ActiveSession.submit(request: RequestId, text: String, trust: TrustLevel?): TurnId {
    val trusted = trust?.takeIf { features.resolve(AppliesTrustLevels) is FeatureAccess.Available }
    val prompt = PromptRequest(request, listOf(ContentPart.Text(text)), trust = trusted)
    return try {
        features.resolve(SendsPrompts).orThrow().send(prompt)
    } catch (e: EngineException) {
        val isAmbiguous = (e.failure as? EngineFailure.Request)?.reason == RequestFailureReason.OutcomeUnknown
        val started = state.value.activeTurn()?.takeIf { isAmbiguous && it.request == request }
        if (started == null) {
            if (state.value is ActiveSessionState.Unavailable) synchronize()
            throw e
        }
        log.w(e) { "submission of ${request.value} has an unknown outcome; following its turn" }
        started.id
    }
}

/**
 * Waits for [turn] to end, reporting the permission requests of that turn whenever they change. Each time the
 * session becomes unavailable it is synchronized (see [recover]). A session that runs the turn no more and does not
 * report it as its last one lost it: the outcome is unknown. A closed handle ends the wait.
 */
internal suspend fun ActiveSession.awaitTurn(
    turn: TurnId,
    onPending: suspend (List<PermissionRequest>) -> Unit,
): TurnOutcome {
    var pending = emptyList<PermissionRequest>()
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
        outcome = current.endOf(turn) ?: if (current is ActiveSessionState.Unavailable) recover(turn) else null
        outcome != null
    }
    if (pending.isNotEmpty()) onPending(emptyList())
    return outcome ?: TurnOutcome.Unknown
}

/**
 * Synchronizes an unavailable session with growing pauses until it leaves that state or tells how [turn] ended.
 * Synchronization may leave the state as it was, which emits nothing new, so the state is read again after each
 * attempt; a session that stays unavailable fails the turn with its failure instead of waiting forever.
 */
private suspend fun ActiveSession.recover(turn: TurnId): TurnOutcome? {
    var pause = SYNC_PAUSE
    repeat(SYNC_ATTEMPTS) { attempt ->
        if (attempt > 0) {
            delay(pause)
            pause *= 2
        }
        synchronize()
        val current = state.value as? ActiveSessionState.Unavailable ?: return state.value.endOf(turn)
        (current.endOf(turn) ?: current.lostTurn())?.let { return it }
    }
    val failure = (state.value as? ActiveSessionState.Unavailable)?.failure ?: return state.value.endOf(turn)
    log.w(EngineException(failure)) { "session on ${ref.engine.value} stayed unavailable; its turn failed" }
    return TurnOutcome.Failed(failure)
}

/** Cancels the running turn, waits up to [wait] for it to stop and closes the handle, even when stopping fails. */
internal suspend fun ActiveSession.stop(wait: Duration) {
    try {
        val turn = state.value.activeTurn()
        if (turn != null) {
            cancelQuietly(turn.id)
            withTimeoutOrNull(wait) { state.first { it.activeTurn() == null } }
                ?: log.w { "turn on ${ref.engine.value} did not stop in time; closing the handle" }
        }
    } finally {
        withContext(NonCancellable) { close() }
    }
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
    // A session that is ready again but reports another turn as its last one no longer knows this turn.
    is ActiveSessionState.Ready -> if (lastTurn?.id == turn) lastTurn?.outcome else TurnOutcome.Unknown

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

private const val SYNC_ATTEMPTS = 6
private val SYNC_PAUSE = 2.seconds
