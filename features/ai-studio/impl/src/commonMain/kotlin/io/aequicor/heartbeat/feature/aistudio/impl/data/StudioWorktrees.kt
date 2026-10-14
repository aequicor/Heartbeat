package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout

/** Studio talks to the durable worktree machine exclusively through its public contract. */
@Inject
@OptIn(ExperimentalCoroutinesApi::class)
internal class StudioWorktrees(private val machines: MachineRegistry, private val tools: ProfileAgentTools) {
    private val log = Log.tag("StudioWorktrees")

    fun tasks(): Flow<Map<String, WorktreeTask>> = machines.observe(WorktreeMachineKey).flatMapLatest { machine ->
        machine?.state?.map { (it as? WorktreeState.Ready)?.tasks.orEmpty() } ?: flowOf(emptyMap())
    }

    /** Only a loaded journal can establish that a saved task is absent. */
    fun readyTasks(): Flow<Map<String, WorktreeTask>> = machines.observe(WorktreeMachineKey).flatMapLatest { machine ->
        machine?.state?.mapNotNull { (it as? WorktreeState.Ready)?.tasks } ?: flowOf()
    }

    suspend fun send(intent: WorktreeIntent.Public) {
        val machine = withTimeout(WAIT_MILLIS) { machines.observe(WorktreeMachineKey).filterNotNull().first() }
        withTimeout(WAIT_MILLIS) {
            machine.state.first { it is WorktreeState.Ready || it is WorktreeState.LoadError }
        }
        check(machine.state.value is WorktreeState.Ready) { "Worktree journal is unavailable" }
        check(machines.send(WorktreeMachineKey, intent) == SendResult.Accepted) { "Worktree request rejected" }
    }

    suspend fun await(
        id: String,
        failOnTaskError: Boolean = true,
        predicate: (WorktreeTask) -> Boolean,
    ): WorktreeTask = withTimeout(WAIT_MILLIS) {
        tasks().mapNotNull { it[id] }.first {
            predicate(it) || (
                failOnTaskError &&
                    (it.phase == WorktreePhase.Failed || it.phase == WorktreePhase.RecoveryRequired)
            )
        }.also { check(predicate(it)) { "Worktree operation failed: ${it.failure.orEmpty()}" } }
    }

    /** Explicit continuation inspects the existing session and never repeats an interrupted prompt. */
    suspend fun reconcile(id: String, open: suspend () -> ActiveSession) {
        val previous = tasks().first()[id] ?: return
        val run = previous.run ?: return
        if (previous.phase != WorktreePhase.RecoveryRequired || run.outcome != null) return
        val active = open()
        check(run.session == null || run.session == active.ref) { "The interrupted request belongs to another session" }
        (active.features.resolve(ReconcilesSession) as? FeatureAccess.Available)?.feature?.synchronize()
        if (run.turn == null && run.session != null) {
            reconcileSubmission(id, run.request, active)
            await(id, failOnTaskError = false) { it.run?.outcome != null || it.run == null }
            return
        }
        val native = active.state.value
        check(
            native is ActiveSessionState.Ready ||
                (native is ActiveSessionState.Unavailable && native.activeTurn == null),
        ) {
            "The interrupted native turn is still active"
        }
        val turn = run.turn
        if (turn == null) {
            send(WorktreeIntent.Public.RunRejected(id, run.request, "InterruptedBeforeAcceptance"))
        } else {
            tools.finishTurn(active.ref, turn)
            val last = native.lastCompletedTurn()
            send(
                WorktreeIntent.Public.RunSettled(
                    id,
                    run.request,
                    active.ref,
                    turn,
                    last?.takeIf { it.id == turn }?.outcome ?: TurnOutcome.Unknown,
                ),
            )
        }
        await(id, failOnTaskError = false) { it.run?.outcome != null || it.run == null }
    }

    /** Fresh idle releases unknown ownership; a terminal receipt must belong to this exact pending request. */
    private suspend fun reconcileSubmission(id: String, request: RequestId, active: ActiveSession) {
        val inspector = (active.features.resolve(RestoresSessionTurns) as? FeatureAccess.Available)?.feature
        val inspection = inspector?.inspect(null)
        check(inspection.allowsSubmissionRecovery(request)) {
            "The interrupted submission's native outcome is still unknown"
        }
        val native = active.state.value
        check(
            native is ActiveSessionState.Ready ||
                (native is ActiveSessionState.Unavailable && native.activeTurn == null),
        ) { "The interrupted native turn is still active" }
        val terminal = native.submissionTerminal(request, inspection)
        if (terminal != null) {
            tools.finishTurn(active.ref, terminal.id)
            send(WorktreeIntent.Public.RunSettled(id, request, active.ref, terminal.id, checkNotNull(terminal.outcome)))
        } else {
            check(inspection == TurnInspection.Idle) { "The interrupted submission's native outcome is still unknown" }
            send(WorktreeIntent.Public.RunSubmissionReconciled(id, request, active.ref))
        }
    }

    /** Acceptance delivery failure does not abandon an already accepted native request. */
    suspend fun accepted(id: String, request: RequestId, session: SessionRef, turn: TurnId) {
        safely("Could not record acceptance; continue observing the native turn") {
            send(WorktreeIntent.Public.RunAccepted(id, request, session, turn))
        }
    }

    /** Main-checkout tasks have their own ids; native identity locates every task owned by this request. */
    suspend fun settled(id: String?, request: RequestId, session: SessionRef, turn: TurnId, outcome: TurnOutcome) {
        val ids = if (id != null) listOf(id) else matchingTasks(session, request, turn).map { it.chatId }
        ids.forEach { send(WorktreeIntent.Public.RunSettled(it, request, session, turn, outcome)) }
    }

    suspend fun failed(id: String?, request: RequestId, session: SessionRef?, turn: TurnId?) {
        safely("Could not record interrupted worktree turn; recovery must inspect the journal") {
            if (turn == null) {
                if (id != null) send(WorktreeIntent.Public.RunRejected(id, request, "RequestRejected"))
            } else {
                settled(id, request, checkNotNull(session), turn, TurnOutcome.Unknown)
            }
        }
    }

    suspend fun submissionObservationLost(id: String, request: RequestId, session: SessionRef) {
        safely("Could not record submission observation loss; pending ownership is retained") {
            send(WorktreeIntent.Public.RunSubmissionObservationLost(id, request, session, "NativeOutcomeUnknown"))
        }
    }

    suspend fun observationLost(id: String?, request: RequestId, active: ActiveSession, turn: TurnId) {
        safely("Could not record observation loss; native ownership is retained") {
            val ids = if (id != null) listOf(id) else matchingTasks(active.ref, request, turn).map { it.chatId }
            ids.forEach {
                send(WorktreeIntent.Public.RunObservationLost(it, request, active.ref, turn, "NativeOutcomeUnknown"))
            }
        }
    }

    suspend fun cancelBuilds(session: SessionRef, request: RequestId, turn: TurnId) {
        safely("Could not enqueue build cancellation; continue stopping the native turn") {
            matchingTasks(session, request, turn).forEach { task ->
                task.builds.values.filter {
                    it.phase in setOf(
                        WorktreeBuildPhase.Queued,
                        WorktreeBuildPhase.WaitingForResource,
                        WorktreeBuildPhase.Running,
                    )
                }.forEach { send(WorktreeIntent.Public.CancelBuild(task.chatId, it.id)) }
            }
        }
    }

    private suspend fun matchingTasks(session: SessionRef, request: RequestId, turn: TurnId): List<WorktreeTask> =
        tasks().first().values.filter {
            it.run?.let { run -> run.session == session && run.request == request && run.turn == turn } == true
        }

    private suspend fun safely(message: String, operation: suspend () -> Unit) {
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { message }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 120_000L
    }
}

internal fun ActiveSessionState.lastCompletedTurn() = when (this) {
    is ActiveSessionState.Ready -> lastTurn

    is ActiveSessionState.Unavailable -> lastTurn

    is ActiveSessionState.Submitting, is ActiveSessionState.Running, is ActiveSessionState.AwaitingUserAction,
    is ActiveSessionState.Interrupting, is ActiveSessionState.Closing, ActiveSessionState.Closed,
    -> null
}

internal fun ActiveSessionState.isTerminalFor(turn: TurnId): Boolean =
    (this is ActiveSessionState.Ready && lastTurn?.id == turn) ||
        (this is ActiveSessionState.Unavailable && activeTurn == null && lastTurn?.id == turn)

/** Unknown inspection or an unrelated/running receipt cannot release an uncertain submission. */
private fun TurnInspection?.allowsSubmissionRecovery(request: RequestId): Boolean = when (this) {
    null, TurnInspection.Idle -> true
    TurnInspection.Unknown -> false
    is TurnInspection.Observed -> this.request == request && outcome != null
}

/** A synthetic Unknown needs fresh inspection; a receipt and local terminal state must agree. */
private fun ActiveSessionState.submissionTerminal(request: RequestId, inspection: TurnInspection?): Turn? {
    val terminal = lastCompletedTurn() ?: return null
    val isRequestTerminal = activeTurn() == null && terminal.request == request && terminal.outcome != null
    val isOutcomeConfirmed = terminal.outcome != TurnOutcome.Unknown || inspection != null
    val isInspectionMatching = inspection !is TurnInspection.Observed || inspection.outcome == terminal.outcome
    return terminal.takeIf { isRequestTerminal && isOutcomeConfirmed && isInspectionMatching }
}
