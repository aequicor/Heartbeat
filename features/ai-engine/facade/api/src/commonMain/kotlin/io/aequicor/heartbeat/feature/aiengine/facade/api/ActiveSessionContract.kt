package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import kotlinx.serialization.Serializable

/** Execution state of a handle. Native execution is owned by the profile runtime, not a state-scoped effect. */
@Serializable
public sealed interface ActiveSessionState : MachineState {
    /** Idle, with the most recent terminal outcome retained for late subscribers. */
    @Serializable
    public data class Ready(val lastTurn: Turn? = null) : ActiveSessionState {
        init {
            require(lastTurn == null || lastTurn.outcome != null)
        }
    }

    /** No acceptance has been observed yet; correlated native completion or permission also proves acceptance. */
    @Serializable
    public data class Submitting(val request: PromptRequest, val turn: Turn) : ActiveSessionState

    /** One accepted turn is executing. */
    @Serializable
    public data class Running(val turn: Turn) : ActiveSessionState

    /** Requests awaiting engine-confirmed decisions. Responding ids prevent duplicate in-flight decisions. */
    @Serializable
    public data class AwaitingUserAction(
        val turn: Turn,
        val requests: List<PermissionRequest>,
        val responding: Set<PermissionRequestId> = emptySet(),
    ) : ActiveSessionState {
        init {
            require(turn.outcome == null)
            require(requests.isNotEmpty() && requests.all { it.turn == turn.id && it.id !in turn.resolvedPermissions })
            require(requests.map { it.id }.distinct().size == requests.size)
            require(responding.all { id -> requests.any { it.id == id } })
        }
    }

    /** Cancellation was requested; the native terminal outcome is still unknown. */
    @Serializable
    public data class Interrupting(val turn: Turn) : ActiveSessionState

    /** Recovery requires synchronization with the runtime; activeTurn may still be executing remotely. */
    @Serializable
    public data class Unavailable(
        val failure: EngineFailure,
        val activeTurn: Turn? = null,
        val lastTurn: Turn? = null,
    ) : ActiveSessionState {
        init {
            require(activeTurn == null || activeTurn.outcome == null)
            require(lastTurn == null || lastTurn.outcome != null)
        }
    }

    /** Detachment is in flight, or failed and can be retried by close(). No new commands are accepted. */
    @Serializable
    public data class Closing(val failure: EngineFailure? = null) : ActiveSessionState

    /** The handle is detached. Native history and profile-owned execution may still exist. */
    @Serializable
    public data object Closed : ActiveSessionState
}

/** Public commands and correlated runtime observations. Unknown/stale turn and permission ids are ignored. */
public sealed interface ActiveSessionIntent : MachineIntent {
    /** Commands accepted through the facade wrapper. */
    public sealed interface Public : ActiveSessionIntent {
        /** Starts submission with a locally allocated turn id; send completes only after Accepted. */
        public data class Submit(val request: PromptRequest, val turn: Turn) : Public {
            init {
                require(turn.request == request.id && turn.outcome == null)
            }
        }

        /** Requests cancellation of the current accepted turn. */
        public data class Cancel(val turn: TurnId) : Public

        /** Answers one currently pending permission request. */
        public data class Decide(val decision: PermissionDecision) : Public

        /** Reconciles unavailable state without resending the original prompt. */
        public data object Recheck : Public

        /** Releases this handle, independently of native execution. */
        public data object Close : Public
    }

    /** Results from effects and the profile-owned runtime event bridge. */
    public sealed interface Internal : ActiveSessionIntent {
        /** Native request acceptance. */
        public data class Accepted(val turn: TurnId) : Internal

        /** Native turn completion, including cancellation races. */
        public data class Finished(val turn: TurnId, val outcome: TurnOutcome) : Internal

        /** A pending native permission request. */
        public data class PermissionNeeded(val request: PermissionRequest) : Internal

        /** Native acknowledgement; sending a decision alone does not remove the pending request. */
        public data class PermissionResolved(val turn: TurnId, val request: PermissionRequestId) : Internal

        /** An operation failed; null turn identifies a lifecycle or reconciliation operation. */
        public data class Failed(val turn: TurnId?, val failure: EngineFailure) : Internal

        /** Lease release completed; only this acknowledgement makes the handle Closed. */
        public data object Released : Internal

        /**
         * Authoritative runtime reconciliation, never a local assumption that a remote turn stopped.
         * Active turns include all previously acknowledged permission ids, including decisions from other clients.
         * Before publishing, look up the outcome of the turn requested by Recheck if it is no longer active.
         * [completed] carries that correlated outcome; null means native outcome recovery was exhausted.
         * A displaced remembered turn is reported once as Finished, using Unknown when its outcome is unavailable.
         */
        public data class Synchronized(
            val active: Turn?,
            val pending: List<PermissionRequest> = emptyList(),
            val completed: Finished? = null,
        ) : Internal {
            init {
                require(active == null || active.outcome == null)
                require(completed == null || completed.turn != active?.id)
                require(pending.all { it.turn == active?.id && it.id !in active.resolvedPermissions })
                require(pending.map { it.id }.distinct().size == pending.size)
            }
        }
    }
}

/**
 * Short commands handed to a profile-owned runtime. Effects must not own the native turn or its subscription:
 * changing machine states cancels state effects, while accepted runtime work must continue until native completion.
 * Emit acknowledgements only after command handoff. Release detaches a handle, never cancels a native turn.
 */
public sealed interface ActiveSessionEffect : MachineEffect {
    /** Submit exactly once using the request correlation id. */
    public data class Submit(val request: PromptRequest, val turn: Turn) : ActiveSessionEffect

    /** Request native interruption. */
    public data class Cancel(val turn: TurnId) : ActiveSessionEffect

    /** Deliver a decision to an outstanding request. */
    public data class Decide(val decision: PermissionDecision) : ActiveSessionEffect

    /** Read native state and recover this remembered turn's outcome if it ended; never replay a prompt. */
    public data class Recheck(val turn: TurnId?) : ActiveSessionEffect

    /** Detach this handle's observation lease. */
    public data object Release : ActiveSessionEffect
}

/** Transient notifications; state/history remain authoritative for subscribers attaching later. */
public sealed interface ActiveSessionOutput : MachineOutput {
    /** send() may now complete successfully. */
    public data class Accepted(val turn: Turn) : ActiveSessionOutput

    /** One terminal observation, including an explicit Unknown when native outcome recovery was exhausted. */
    public data class Finished(val turn: Turn) : ActiveSessionOutput

    /** send() must fail with this reason; it is not an accepted-turn failure. */
    public data class SubmissionFailed(val request: RequestId, val failure: EngineFailure) : ActiveSessionOutput
}

/** Per-handle key. The safe local id must not contain native session paths, accounts or credentials. */
public class ActiveSessionMachineKey(handleId: String) :
    MachineKey<
        ActiveSessionState,
        ActiveSessionIntent,
        ActiveSessionIntent.Public,
        ActiveSessionEffect,
        ActiveSessionOutput,
    > {
    init {
        require(handleId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }
    override val name: String = "ai-session.$handleId"
}

internal fun ActiveSessionState.currentTurn(): Turn? = when (this) {
    is ActiveSessionState.Ready, is ActiveSessionState.Closing, ActiveSessionState.Closed -> null
    is ActiveSessionState.Submitting -> turn
    is ActiveSessionState.Running -> turn
    is ActiveSessionState.AwaitingUserAction -> turn
    is ActiveSessionState.Interrupting -> turn
    is ActiveSessionState.Unavailable -> activeTurn
}
