package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineSpecBuilder
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec

private typealias SessionSpecBuilder =
    MachineSpecBuilder<ActiveSessionState, ActiveSessionIntent, ActiveSessionEffect, ActiveSessionOutput>
private typealias SessionStateBuilder<S> =
    StateBuilder<ActiveSessionState, S, ActiveSessionIntent, ActiveSessionEffect, ActiveSessionOutput>

/**
 * Pure lifecycle specification; no persistence and no IO. Initial state comes from an atomic native snapshot.
 *
 * | From | Intent | To | Effect / output |
 * | Ready | Submit | Submitting | Submit |
 * | Submitting | Accepted | Running | Accepted |
 * | Submitting | Failed | Unavailable | SubmissionFailed |
 * | Submitting | Finished | Ready | Accepted, then Finished |
 * | Submitting | PermissionNeeded | AwaitingUserAction | Accepted; pending request retained |
 * | Running | PermissionNeeded | AwaitingUserAction | pending request retained |
 * | AwaitingUserAction | Decide | same | Decide; duplicate decisions ignored |
 * | AwaitingUserAction | PermissionResolved | Running / same | acknowledgement removes request |
 * | Running / AwaitingUserAction | Cancel | Interrupting | Cancel |
 * | Running / AwaitingUserAction / Interrupting | Finished | Ready | Finished |
 * | active | Failed | Unavailable | never assume a remote turn stopped |
 * | Unavailable | Recheck | same | recover remembered turn; never resubmit |
 * | Unavailable | Synchronized | reconciled state | Finished for a displaced remembered turn, even if Unknown |
 * | Unavailable | Finished | same, terminal outcome retained | Finished |
 * | live | Close | Closing | Release; native turn continues |
 * | Closing | Released / Failed | Closed / retryable Closing | no implicit cleanup success |
 *
 * A profile-owned event bridge delivers native observations independently of state-scoped effects. The facade
 * translates ignored commands into domain errors and waits for native acceptance before completing send().
 * Correlated completion/permission proves acceptance even when the explicit acknowledgement arrives later.
 * Reconciliation looks up the remembered turn before replacing it; unavailable native outcomes stay explicit.
 *
 * Effect failures map to Failed: EngineException keeps its domain failure; anything else, including a
 * cancellation escaping from inside a running effect, becomes OutcomeUnknown for Submit and Unknown otherwise.
 */
public fun activeSessionMachineSpec(
    key: ActiveSessionMachineKey,
    initial: ActiveSessionState,
): MachineSpec<ActiveSessionState, ActiveSessionIntent, ActiveSessionEffect, ActiveSessionOutput> =
    machineSpec(key, initial.validatedInitial()) {
        submissionStates()
        executionStates()
        permissionState()
        recoveryState()
        closingStates()
        any {
            on<ActiveSessionIntent.Public.Close>(
                guard = { state != ActiveSessionState.Closed && state !is ActiveSessionState.Closing },
            ) {
                goto<ActiveSessionState.Closing> { ActiveSessionState.Closing() }
                effect { ActiveSessionEffect.Release }
            }
            on<ActiveSessionIntent.Internal.Failed>(guard = {
                state != ActiveSessionState.Closed && state !is ActiveSessionState.Closing &&
                    intent.turn == state.currentTurn()?.id
            }) {
                goto<ActiveSessionState.Unavailable> {
                    ActiveSessionState.Unavailable(
                        intent.failure,
                        state.currentTurn(),
                        (state as? ActiveSessionState.Ready)?.lastTurn,
                    )
                }
            }
        }
        // The runtime only reports cancellations escaping from inside a still-current effect (e.g. a timeout);
        // they must still fail the effect, otherwise Submitting/Interrupting/Closing would never be left.
        onEffectFailure { effect, error -> effect.failureIntent(error) }
    }

private fun SessionSpecBuilder.submissionStates() {
    state<ActiveSessionState.Ready> {
        on<ActiveSessionIntent.Public.Submit> {
            goto<ActiveSessionState.Submitting> { ActiveSessionState.Submitting(intent.request, intent.turn) }
            effect { ActiveSessionEffect.Submit(intent.request, intent.turn) }
        }
    }
    state<ActiveSessionState.Submitting> {
        terminalTurn()
        pendingPermission()
        on<ActiveSessionIntent.Public.Close> {
            goto<ActiveSessionState.Closing> { ActiveSessionState.Closing() }
            effect { ActiveSessionEffect.Release }
            output {
                ActiveSessionOutput.SubmissionFailed(
                    state.request.id,
                    EngineFailure.Request(RequestFailureReason.OutcomeUnknown, state.request.id),
                )
            }
        }
        on<ActiveSessionIntent.Internal.Accepted>(guard = { intent.turn == state.turn.id }) {
            goto<ActiveSessionState.Running> { ActiveSessionState.Running(state.turn) }
            output { ActiveSessionOutput.Accepted(state.turn) }
        }
        on<ActiveSessionIntent.Internal.Failed>(guard = { intent.turn == state.turn.id }) {
            goto<ActiveSessionState.Unavailable> { ActiveSessionState.Unavailable(intent.failure, state.turn) }
            output { ActiveSessionOutput.SubmissionFailed(state.request.id, intent.failure) }
        }
    }
}

private fun SessionSpecBuilder.executionStates() {
    state<ActiveSessionState.Running> {
        terminalTurn()
        cancellableTurn()
        pendingPermission()
    }
    state<ActiveSessionState.Interrupting> { terminalTurn() }
}

private fun <S : ActiveSessionState> SessionStateBuilder<S>.terminalTurn() {
    on<ActiveSessionIntent.Internal.Finished>(guard = { intent.turn == state.currentTurn()?.id }) {
        goto<ActiveSessionState.Ready> {
            ActiveSessionState.Ready(
                checkNotNull(state.currentTurn()).copy(outcome = intent.outcome),
            )
        }
        output { (state as? ActiveSessionState.Submitting)?.let { ActiveSessionOutput.Accepted(it.turn) } }
        output { ActiveSessionOutput.Finished(checkNotNull(state.currentTurn()).copy(outcome = intent.outcome)) }
    }
}

private fun <S : ActiveSessionState> SessionStateBuilder<S>.pendingPermission() {
    on<ActiveSessionIntent.Internal.PermissionNeeded>(guard = {
        val turn = state.currentTurn()
        turn != null && intent.request.turn == turn.id && intent.request.id !in turn.resolvedPermissions
    }) {
        goto<ActiveSessionState.AwaitingUserAction> {
            ActiveSessionState.AwaitingUserAction(checkNotNull(state.currentTurn()), listOf(intent.request))
        }
        output { (state as? ActiveSessionState.Submitting)?.let { ActiveSessionOutput.Accepted(it.turn) } }
    }
}

private fun <S : ActiveSessionState> SessionStateBuilder<S>.cancellableTurn() {
    on<ActiveSessionIntent.Public.Cancel>(guard = { intent.turn == state.currentTurn()?.id }) {
        goto<ActiveSessionState.Interrupting> { ActiveSessionState.Interrupting(checkNotNull(state.currentTurn())) }
        effect { ActiveSessionEffect.Cancel(intent.turn) }
    }
}

private fun SessionSpecBuilder.permissionState() {
    state<ActiveSessionState.AwaitingUserAction> {
        terminalTurn()
        cancellableTurn()
        on<ActiveSessionIntent.Internal.PermissionNeeded>(guard = {
            intent.request.turn == state.turn.id && intent.request.id !in state.turn.resolvedPermissions
        }) {
            stay { state.copy(requests = state.requests.filterNot { it.id == intent.request.id } + intent.request) }
        }
        on<ActiveSessionIntent.Public.Decide>(guard = {
            intent.decision.turn == state.turn.id && intent.decision.request !in state.responding &&
                state.requests.any { request -> request.accepts(intent.decision) }
        }) {
            stay { state.copy(responding = state.responding + intent.decision.request) }
            effect { ActiveSessionEffect.Decide(intent.decision) }
        }
        permissionAcknowledgements()
    }
}

private fun SessionStateBuilder<ActiveSessionState.AwaitingUserAction>.permissionAcknowledgements() {
    on<ActiveSessionIntent.Internal.PermissionResolved>(guard = {
        intent.turn == state.turn.id && state.requests.size == 1 && state.requests.single().id == intent.request
    }) {
        goto<ActiveSessionState.Running> {
            ActiveSessionState.Running(
                state.turn.copy(resolvedPermissions = state.turn.resolvedPermissions + intent.request),
            )
        }
    }
    on<ActiveSessionIntent.Internal.PermissionResolved>(guard = {
        intent.turn == state.turn.id && state.requests.size > 1 && state.requests.any { it.id == intent.request }
    }) {
        stay {
            state.copy(
                turn = state.turn.copy(resolvedPermissions = state.turn.resolvedPermissions + intent.request),
                requests = state.requests.filterNot { it.id == intent.request },
                responding = state.responding - intent.request,
            )
        }
    }
}

private fun SessionSpecBuilder.recoveryState() {
    state<ActiveSessionState.Unavailable> {
        on<ActiveSessionIntent.Internal.Finished>(guard = { intent.turn == state.activeTurn?.id }) {
            stay {
                state.copy(activeTurn = null, lastTurn = checkNotNull(state.activeTurn).copy(outcome = intent.outcome))
            }
            output { ActiveSessionOutput.Finished(checkNotNull(state.activeTurn).copy(outcome = intent.outcome)) }
        }
        on<ActiveSessionIntent.Public.Recheck> { effect { ActiveSessionEffect.Recheck(state.activeTurn?.id) } }
        on<ActiveSessionIntent.Internal.Failed>(
            guard = { intent.turn == null || intent.turn == state.activeTurn?.id },
        ) {
            stay { state.copy(failure = intent.failure) }
        }
        on<ActiveSessionIntent.Internal.Synchronized>(guard = { intent.active == null }) {
            goto<ActiveSessionState.Ready> { ActiveSessionState.Ready(intent.finishedTurn(state) ?: state.lastTurn) }
            output { intent.finishedTurn(state)?.let { ActiveSessionOutput.Finished(it) } }
        }
        on<ActiveSessionIntent.Internal.Synchronized>(guard = {
            intent.active != null && intent.active?.id != state.lastTurn?.id && intent.pending.isEmpty()
        }) {
            goto<ActiveSessionState.Running> { ActiveSessionState.Running(checkNotNull(intent.active)) }
            output { intent.finishedTurn(state)?.let { ActiveSessionOutput.Finished(it) } }
        }
        on<ActiveSessionIntent.Internal.Synchronized>(
            guard = {
                intent.active != null && intent.active?.id != state.lastTurn?.id && intent.pending.isNotEmpty()
            },
        ) {
            goto<ActiveSessionState.AwaitingUserAction> {
                ActiveSessionState.AwaitingUserAction(checkNotNull(intent.active), intent.pending)
            }
            output { intent.finishedTurn(state)?.let { ActiveSessionOutput.Finished(it) } }
        }
    }
}

private fun ActiveSessionIntent.Internal.Synchronized.finishedTurn(state: ActiveSessionState.Unavailable): Turn? {
    val previous = state.activeTurn?.takeIf { it.id != active?.id } ?: return null
    val outcome = completed?.takeIf { it.turn == previous.id }?.outcome ?: TurnOutcome.Unknown
    return previous.copy(outcome = outcome)
}

private fun ActiveSessionState.validatedInitial(): ActiveSessionState {
    require(
        this is ActiveSessionState.Ready || this is ActiveSessionState.Running ||
            this is ActiveSessionState.AwaitingUserAction || this is ActiveSessionState.Unavailable,
    )
    require(currentTurn()?.outcome == null)
    return this
}

private fun SessionSpecBuilder.closingStates() {
    state<ActiveSessionState.Closing> {
        on<ActiveSessionIntent.Internal.Released> {
            goto<ActiveSessionState.Closed> { ActiveSessionState.Closed }
        }
        on<ActiveSessionIntent.Internal.Failed>(guard = { intent.turn == null }) {
            stay { state.copy(failure = intent.failure) }
        }
        on<ActiveSessionIntent.Public.Close>(guard = { state.failure != null }) {
            stay { ActiveSessionState.Closing() }
            effect { ActiveSessionEffect.Release }
        }
    }
    state<ActiveSessionState.Closed> { on<ActiveSessionIntent.Public.Close>() }
}

private fun ActiveSessionEffect.failureIntent(error: Throwable): ActiveSessionIntent.Internal.Failed {
    val turn = when (this) {
        is ActiveSessionEffect.Submit -> turn.id
        is ActiveSessionEffect.Cancel -> turn
        is ActiveSessionEffect.Decide -> decision.turn
        is ActiveSessionEffect.Recheck, ActiveSessionEffect.Release -> null
    }
    val failure = if (error is EngineException) {
        error.failure
    } else {
        when (this) {
            is ActiveSessionEffect.Submit -> EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id)

            is ActiveSessionEffect.Cancel, is ActiveSessionEffect.Decide,
            is ActiveSessionEffect.Recheck, ActiveSessionEffect.Release,
            -> EngineFailure.Unknown()
        }
    }
    return ActiveSessionIntent.Internal.Failed(turn, failure)
}
