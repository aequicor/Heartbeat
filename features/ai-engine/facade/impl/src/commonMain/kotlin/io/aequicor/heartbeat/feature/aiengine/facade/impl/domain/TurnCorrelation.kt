package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId

/**
 * Maps native turn ids to the ids this handle allocated for its own submissions. A native turn is bound to a
 * local one by the returned native id or by the request correlation id; every other turn keeps its native id.
 * Confined to the main thread, like the machine that uses it.
 */
class TurnCorrelation {
    private val toLocal = mutableMapOf<TurnId, TurnId>()
    private val toNative = mutableMapOf<TurnId, TurnId>()

    /** Binds [native] to [local]. */
    fun bind(native: TurnId, local: TurnId) {
        toLocal[native] = local
        toNative[local] = native
    }

    /** Native id of a local turn. */
    fun native(local: TurnId): TurnId = toNative[local] ?: local

    /** Decision addressed to the native turn. */
    fun native(decision: PermissionDecision): PermissionDecision = decision.copy(turn = native(decision.turn))

    /**
     * Native [state] in local ids. While [machine] is submitting, a native turn carrying the same request id is
     * bound to the pending local turn first.
     */
    fun localize(state: ActiveSessionState, machine: ActiveSessionState): ActiveSessionState {
        if (machine is ActiveSessionState.Submitting) {
            listOfNotNull(state.activeTurn(), state.lastTurn())
                .firstOrNull { it.request == machine.request.id && it.id !in toLocal }
                ?.let { bind(it.id, machine.turn.id) }
        }
        return localize(state)
    }

    /** Native [state] in local ids, without binding new turns. */
    fun localize(state: ActiveSessionState): ActiveSessionState = state.mapTurnIds { toLocal[it] ?: it }
}

/**
 * Intents that move [machine] towards the native [native] state, both in local ids. Unknown outcomes are never
 * assumed: when the native side no longer shows this handle's turn and did not report its outcome, the handle
 * becomes Unavailable and recovers through Recheck.
 */
fun reconcile(machine: ActiveSessionState, native: ActiveSessionState): List<ActiveSessionIntent.Internal> =
    when (machine) {
        is ActiveSessionState.Submitting -> submitting(machine.turn, native)
        is ActiveSessionState.Running -> active(machine.turn, emptyList(), native)
        is ActiveSessionState.Interrupting -> active(machine.turn, emptyList(), native)
        is ActiveSessionState.AwaitingUserAction -> active(machine.turn, machine.requests, native)
        is ActiveSessionState.Ready -> ready(machine, native)
        is ActiveSessionState.Unavailable -> unavailable(machine, native)
        is ActiveSessionState.Closing, ActiveSessionState.Closed -> emptyList()
    }

private fun submitting(turn: Turn, native: ActiveSessionState): List<ActiveSessionIntent.Internal> {
    val finished = native.lastTurn()?.takeIf { it.id == turn.id }
    return when {
        native.activeTurn()?.id == turn.id -> native.pending().map { ActiveSessionIntent.Internal.PermissionNeeded(it) }
            .ifEmpty { listOf(ActiveSessionIntent.Internal.Accepted(turn.id)) }

        finished != null -> listOf(ActiveSessionIntent.Internal.Finished(turn.id, checkNotNull(finished.outcome)))

        else -> emptyList()
    }
}

private fun active(
    turn: Turn,
    requests: List<PermissionRequest>,
    native: ActiveSessionState,
): List<ActiveSessionIntent.Internal> {
    val nativeTurn = native.activeTurn()
    val finished = native.lastTurn()?.takeIf { it.id == turn.id }
    return when {
        finished != null -> listOf(ActiveSessionIntent.Internal.Finished(turn.id, checkNotNull(finished.outcome)))

        !native.isReachable() -> listOf(ActiveSessionIntent.Internal.Failed(turn.id, native.lostTurnFailure()))

        nativeTurn?.id == turn.id -> {
            val pending = native.pending()
            val resolved = requests
                .filter { request ->
                    pending.none { it.id == request.id } &&
                        request.id in nativeTurn.resolvedPermissions
                }
                .map { ActiveSessionIntent.Internal.PermissionResolved(turn.id, it.id) }
            resolved + pending.filter { request -> requests.none { it.id == request.id } }
                .map { ActiveSessionIntent.Internal.PermissionNeeded(it) }
        }

        else -> listOf(ActiveSessionIntent.Internal.Failed(turn.id, native.lostTurnFailure()))
    }
}

private fun ready(machine: ActiveSessionState.Ready, native: ActiveSessionState): List<ActiveSessionIntent.Internal> {
    val nativeTurn = native.activeTurn()
    return if (nativeTurn != null && nativeTurn.id != machine.lastTurn?.id) {
        listOf(ActiveSessionIntent.Internal.Failed(null, EngineFailure.Session(SessionFailureReason.Changed)))
    } else {
        emptyList()
    }
}

private fun unavailable(
    machine: ActiveSessionState.Unavailable,
    native: ActiveSessionState,
): List<ActiveSessionIntent.Internal> {
    val turn = machine.activeTurn ?: return emptyList()
    val finished = native.lastTurn()?.takeIf { it.id == turn.id } ?: return emptyList()
    return listOf(ActiveSessionIntent.Internal.Finished(turn.id, checkNotNull(finished.outcome)))
}

/** Whether the native side can answer a reconciliation. */
fun ActiveSessionState.isReachable(): Boolean =
    this !is ActiveSessionState.Unavailable && this !is ActiveSessionState.Closing && this != ActiveSessionState.Closed

/** Turn executing on the native side, if any. */
fun ActiveSessionState.activeTurn(): Turn? = when (this) {
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
}

/** Latest completed turn, if the state retains one. */
fun ActiveSessionState.lastTurn(): Turn? = when (this) {
    is ActiveSessionState.Ready -> lastTurn

    is ActiveSessionState.Unavailable -> lastTurn

    is ActiveSessionState.Submitting, is ActiveSessionState.Running, is ActiveSessionState.AwaitingUserAction,
    is ActiveSessionState.Interrupting, is ActiveSessionState.Closing, ActiveSessionState.Closed,
    -> null
}

/** Pending permission requests of the native active turn. */
fun ActiveSessionState.pending(): List<PermissionRequest> =
    (this as? ActiveSessionState.AwaitingUserAction)?.requests.orEmpty()

private fun ActiveSessionState.lostTurnFailure(): EngineFailure = when (this) {
    is ActiveSessionState.Unavailable -> failure

    is ActiveSessionState.Closing, ActiveSessionState.Closed -> EngineFailure.Lifecycle(
        LifecycleFailureReason.SessionClosed,
    )

    is ActiveSessionState.Ready, is ActiveSessionState.Submitting, is ActiveSessionState.Running,
    is ActiveSessionState.AwaitingUserAction, is ActiveSessionState.Interrupting,
    -> EngineFailure.Session(SessionFailureReason.Changed)
}

/**
 * Normalizes an attached native state into a valid initial machine state: an unconfirmed native submission is
 * an ambiguous outcome, an interruption in flight is still running, and a closed native handle cannot be used.
 */
fun ActiveSessionState.asInitial(): ActiveSessionState = when (this) {
    is ActiveSessionState.Ready, is ActiveSessionState.Running, is ActiveSessionState.AwaitingUserAction,
    is ActiveSessionState.Unavailable,
    -> this

    is ActiveSessionState.Submitting ->
        ActiveSessionState.Unavailable(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id), turn)

    is ActiveSessionState.Interrupting -> ActiveSessionState.Running(turn)

    is ActiveSessionState.Closing, ActiveSessionState.Closed ->
        fail(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed))
}

private fun ActiveSessionState.mapTurnIds(id: (TurnId) -> TurnId): ActiveSessionState {
    fun Turn.mapped() = copy(id = id(this.id))
    fun PermissionRequest.mapped() = copy(turn = id(turn))
    return when (this) {
        is ActiveSessionState.Ready -> copy(lastTurn = lastTurn?.mapped())
        is ActiveSessionState.Submitting -> copy(turn = turn.mapped())
        is ActiveSessionState.Running -> copy(turn = turn.mapped())
        is ActiveSessionState.AwaitingUserAction -> copy(turn = turn.mapped(), requests = requests.map { it.mapped() })
        is ActiveSessionState.Interrupting -> copy(turn = turn.mapped())
        is ActiveSessionState.Unavailable -> copy(activeTurn = activeTurn?.mapped(), lastTurn = lastTurn?.mapped())
        is ActiveSessionState.Closing, ActiveSessionState.Closed -> this
    }
}
