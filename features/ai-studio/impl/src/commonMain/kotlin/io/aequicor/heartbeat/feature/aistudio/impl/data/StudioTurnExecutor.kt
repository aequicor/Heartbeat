package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.NoStudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolChoices
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The repository supplies persistence and native features; this executor owns a single accepted turn. */
internal interface StudioTurnHost : StudioSessionHost {
    suspend fun isWorktree(id: String): Boolean

    suspend fun submitTurn(active: ActiveSession, request: StudioTurnRequest): TurnId
    suspend fun shouldStop(id: String): Boolean
    suspend fun requestStop(id: String, active: ActiveSession, turn: TurnId)
    suspend fun observeHistory(id: String, history: SessionHistory)
    suspend fun refreshHistory(id: String, history: SessionHistory)
    suspend fun updatePermissions(id: String, state: ActiveSessionState)
    suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome
    suspend fun failedTurn(id: String, error: Exception): RunOutcome
}

/**
 * Records exact acceptance and terminal evidence without coupling the executor to helper storage. The executor
 * isolates failures of these hooks: they never abandon native ownership, settlement or the run outcome.
 */
internal interface StudioTurnObserver {
    suspend fun acceptedTurn(request: StudioTurnRequest, active: ActiveSession, turn: TurnId) = Unit
    suspend fun terminalTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) = Unit
}

internal object NoStudioTurnObserver : StudioTurnObserver

/** Learns that a turn settled with its handle still open, after its history is current; failures are isolated. */
internal fun interface StudioTurnSettlement {
    suspend fun settled(request: StudioTurnRequest, active: ActiveSession, outcome: TurnOutcome)
}

internal val NoStudioTurnSettlement = StudioTurnSettlement { _, _, _ -> }

/** Host-created identity; native tools never supply an action's kind or operation. */
internal data class StudioTurnRequest(
    val id: String,
    val prompt: String,
    val settings: RunSettings,
    val kind: WorktreeRunKind,
    val request: RequestId,
    val attachments: List<ResourceRef> = emptyList(),
    /** Delivered only after native acceptance; not persisted or used as worktree identity. */
    val onAccepted: suspend () -> Unit = {},
    /** Host directives for the engine only (a scheduler wake); never shown in the transcript. */
    val directives: List<String> = emptyList(),
    /** Optional cancellation of scheduled preparation; the native sender calls [StudioSubmissionGate.begin]. */
    val submission: StudioSubmissionGate? = null,
    /** Graph recovery adopts a matching live/finished turn before considering a continuation. */
    val recoveryRequests: List<RequestId> = emptyList(),
    val recoveryCheckpoint: String? = null,
    /** Exact accepted native identity, before [onAccepted]; failures here are not isolated. */
    val onTurnAccepted: suspend (ActiveSession, TurnId) -> Unit = { _, _ -> },
    /** The graph journal records the native outcome before execution is reported complete. */
    val onOutcome: suspend (TurnOutcome) -> Unit = {},
    /** Immutable saved causal requests; host relays them to the actual native identity before prompt hooks. */
    val causes: Set<RequestInitiator> = emptySet(),
    /** The screen submission that created this chat; its claimed harnesses connect before this first prompt. */
    val submissionId: String = "",
    /** A native context compaction instead of [prompt]; it runs no tools, so it needs no worktree isolation. */
    val compaction: ContextCompaction? = null,
    /** Continues the chat in a new native session instead of running a turn; see `StudioRollover`. */
    val isRollover: Boolean = false,
    /**
     * The continuation brief: with [isRollover] the new session receives it instead of the essence, with [compaction]
     * the next turn receives it after the engine's own summary.
     */
    val isBriefed: Boolean = false,
    /** With [isRollover] and [isBriefed]: the handoff summary turn completed, so its answer is the work summary. */
    val isSummarized: Boolean = false,
    /** The outside host routing retained throughout this turn. */
    val environment: StudioChatEnvironment? = null,
    /** Only a user submission may read the profile confirmation level; host jobs retain their own policy. */
    val isUserPrompt: Boolean = false,
    /** Executor-owned evidence recorded at the native send boundary, after preparation. */
    val delivery: StudioPromptDelivery? = null,
) {
    override fun toString(): String = "StudioTurnRequest(id=$id, kind=$kind, attachments=${attachments.size})"
}

/**
 * Confirms terminal native state and revokes tools before any worktree action lease is released. A closed handle
 * ends observation with an unknown outcome, retaining worktree ownership until fresh native reconciliation.
 */
@Inject
internal class StudioTurnExecutor(
    private val worktrees: StudioWorktrees,
    private val tools: ProfileAgentTools,
    private val observer: StudioTurnObserver = NoStudioTurnObserver,
    private val harnesses: StudioHarnessAttach = NoStudioHarnessAttach,
    private val settlement: StudioTurnSettlement = NoStudioTurnSettlement,
) {
    private val log = Log.tag("StudioTurnExecutor")

    suspend fun execute(host: StudioTurnHost, request: StudioTurnRequest): RunOutcome {
        val progress = Progress()
        var environment: AutoCloseable? = null
        try {
            val preselected = request.submissionId.takeIf { it.isNotEmpty() }?.let(harnesses::preselected).orEmpty() +
                request.environment?.preselected.orEmpty()
            progress.isIsolated = request.compaction == null && host.isWorktree(request.id)
            if (progress.isIsolated) prepare(host, request, progress, preselected)
            val opened = host.openEnvironmentSession(request, preselected)
            val active = opened.active
            progress.active = active
            environment = opened.lease
            val boundRequest = request.copy(delivery = progress.delivery)
            return runNative(host, boundRequest, active, progress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Studio execution failed" }
            if (progress.isPrepared || progress.active != null) recover(host, request, progress)
            return host.failedTurn(request.id, error)
        } finally {
            // An interrupted/uncertain native submission must never regain embedded tools.
            if (!progress.delivery.isPending || progress.isTerminal) environment?.close()
            if (request.submissionId.isNotEmpty()) harnesses.release(request.submissionId)
            if (request.submission?.isCancelled == true && progress.isPrepared) {
                withContext(NonCancellable) {
                    worktrees.failed(request.id, request.request, session = null, turn = null)
                }
            }
        }
    }

    /** A new chat's chosen harnesses are connected first; failing to connect never fails the turn. */
    private suspend fun connectHarnesses(request: StudioTurnRequest, active: ActiveSession) {
        if (request.submissionId.isEmpty()) return
        try {
            harnesses.beforeSubmit(request.submissionId, active.ref)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Chosen harnesses were not connected before the first prompt" }
        }
    }

    private suspend fun prepare(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        progress: Progress,
        preselected: ToolChoices,
    ) {
        worktrees.reconcile(request.id) { host.openTurn(request.id, request.settings, preselected) }
        worktrees.send(WorktreeIntent.Public.RunStarted(request.id, request.request, request.kind))
        progress.isPrepared = true
        val prepared = worktrees.await(request.id, failOnTaskError = false) {
            it.run?.let { run ->
                run.request == request.request && (run.isPrepared || it.phase == WorktreePhase.Failed)
            } == true
        }
        check(
            prepared.run?.isPrepared == true &&
                prepared.phase in setOf(WorktreePhase.Working, WorktreePhase.ActionWorking),
        ) {
            "Worktree preparation failed: ${prepared.failure.orEmpty()}"
        }
    }

    private suspend fun runNative(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
    ): RunOutcome = supervisorScope {
        val history = active.features.requireFeature(SessionHistory)
        val observation = launch { host.observeHistory(request.id, history) }
        val permissions = launch { active.state.collect { host.updatePermissions(request.id, it) } }
        try {
            val recovered = recoverGraphTurn(active, request)
            if (recovered == GraphRecovery.Unknown) {
                request.onOutcome(TurnOutcome.Unknown)
                return@supervisorScope host.outcome(request.id, TurnOutcome.Unknown)
            }
            if (recovered is GraphRecovery.Completed) {
                request.onOutcome(recovered.outcome)
                return@supervisorScope host.outcome(request.id, recovered.outcome)
            }
            val turn = submitOrAdopt(host, request, active, recovered as? GraphRecovery.Adopt)
            progress.turn = turn
            observe("acceptance") { observer.acceptedTurn(request, active, turn) }
            request.onTurnAccepted(active, turn)
            notifyAccepted(request)
            if (progress.isIsolated) worktrees.accepted(request.id, request.request, active.ref, turn)
            if (host.shouldStop(request.id)) host.requestStop(request.id, active, turn)
            val terminal = active.state.first { it.isTerminalFor(turn) || it == ActiveSessionState.Closed }
            if (terminal == ActiveSessionState.Closed) {
                closedObservation(request, active, turn, progress)
                return@supervisorScope host.outcome(request.id, TurnOutcome.Unknown)
            }
            progress.isTerminal = true
            tools.finishTurn(active.ref, turn)
            observation.cancelAndJoin()
            host.refreshHistory(request.id, history)
            val completed = checkNotNull(terminal.lastCompletedTurn())
            check(
                completed.request == request.request || completed.request in request.recoveryRequests,
            ) { "Terminal turn belongs to another request" }
            val outcome = checkNotNull(completed.outcome) { "Terminal outcome is unavailable" }
            progress.isTerminalObserved = true
            observe("terminal") { observer.terminalTurn(request, active, turn, outcome, isHistoryCurrent = true) }
            observe("settlement") { settlement.settled(request, active, outcome) }
            request.onOutcome(outcome)
            worktrees.settled(request.id.takeIf { progress.isIsolated }, request.request, active.ref, turn, outcome)
            host.outcome(request.id, outcome)
        } finally {
            observation.cancel()
            permissions.cancel()
        }
    }

    private suspend fun submitOrAdopt(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        recovered: GraphRecovery.Adopt?,
    ): TurnId = if (recovered != null) {
        (request.submission as? StudioRunSubmission)?.adopt()
        recovered.turn
    } else {
        connectHarnesses(request, active)
        host.submitTurn(active, request)
    }

    /** Only a confirmed stopped prior turn permits an automatic continuation in the same chat. */
    private suspend fun recoverGraphTurn(active: ActiveSession, request: StudioTurnRequest): GraphRecovery {
        if (request.recoveryRequests.isEmpty()) return GraphRecovery.Submit
        val inspector = (active.features.resolve(RestoresSessionTurns) as? FeatureAccess.Available)?.feature
        if (inspector != null) return inspectGraphTurn(active, request, inspector.inspect(request.recoveryCheckpoint))
        if (active.state.value is ActiveSessionState.Unavailable) {
            active.features.requireFeature(ReconcilesSession).synchronize()
        }
        return rememberedGraphTurn(active.state.value, request)
    }

    private suspend fun inspectGraphTurn(
        active: ActiveSession,
        request: StudioTurnRequest,
        inspection: TurnInspection,
    ): GraphRecovery = when (inspection) {
        TurnInspection.Idle -> GraphRecovery.Submit

        TurnInspection.Unknown -> GraphRecovery.Unknown

        is TurnInspection.Observed -> if (inspection.request !in request.recoveryRequests) {
            GraphRecovery.Unknown
        } else {
            when (val outcome = inspection.outcome) {
                TurnOutcome.Cancelled -> GraphRecovery.Submit

                TurnOutcome.Unknown -> GraphRecovery.Unknown

                is TurnOutcome.Failed, TurnOutcome.Completed -> GraphRecovery.Completed(outcome)

                null -> {
                    val state = withTimeoutOrNull(RECOVERY_WAIT_MILLIS) {
                        active.state.first {
                            it.activeTurn()?.request == inspection.request ||
                                it.lastCompletedTurn()?.request == inspection.request
                        }
                    }
                    state?.let { rememberedGraphTurn(it, request) } ?: GraphRecovery.Unknown
                }
            }
        }
    }

    private fun rememberedGraphTurn(state: ActiveSessionState, request: StudioTurnRequest): GraphRecovery {
        val running = state.activeTurn()
        if (running != null) {
            return if (state !is ActiveSessionState.Unavailable && running.request in request.recoveryRequests) {
                GraphRecovery.Adopt(running.id)
            } else {
                GraphRecovery.Unknown
            }
        }
        val finished = state.lastCompletedTurn()
        return if (finished?.request in request.recoveryRequests) {
            when (finished?.outcome) {
                TurnOutcome.Completed, is TurnOutcome.Failed -> GraphRecovery.Adopt(finished.id)
                TurnOutcome.Cancelled -> GraphRecovery.Submit
                TurnOutcome.Unknown, null -> GraphRecovery.Unknown
            }
        } else {
            GraphRecovery.Unknown
        }
    }

    private sealed interface GraphRecovery {
        data object Submit : GraphRecovery
        data object Unknown : GraphRecovery
        data class Adopt(val turn: TurnId) : GraphRecovery
        data class Completed(val outcome: TurnOutcome) : GraphRecovery
    }

    /** An observer failure is logged; the turn's ownership, settlement and outcome continue unchanged. */
    private suspend fun observe(hook: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Native turn $hook evidence could not be recorded; continue native ownership" }
        }
    }

    /** Notification failure cannot abandon native ownership or unlock a prepared worktree. */
    private suspend fun notifyAccepted(request: StudioTurnRequest) {
        try {
            request.onAccepted()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Native turn accepted; composer acknowledgement could not be delivered" }
        }
    }

    private suspend fun recover(host: StudioTurnHost, request: StudioTurnRequest, progress: Progress) {
        if (recoverUnaccepted(request, progress)) return
        val active = checkNotNull(progress.active)
        val turn = checkNotNull(progress.turn)
        if (!progress.isTerminal) {
            if (active.state.value != ActiveSessionState.Closed) host.requestStop(request.id, active, turn)
            val stopped = withTimeoutOrNull(RECOVERY_WAIT_MILLIS) {
                active.state.first { it.isTerminalFor(turn) || it == ActiveSessionState.Closed }
            }
            if (stopped == null) observeInterrupted(host, request, active, turn, progress.isIsolated)
            if (active.state.value == ActiveSessionState.Closed) {
                closedObservation(request, active, turn, progress, isObservationLost = stopped == null)
                return
            }
        }
        tools.finishTurn(active.ref, turn)
        progress.isTerminal = true
        val terminal = active.state.value.takeIf { it.isTerminalFor(turn) }?.lastCompletedTurn()
        // The observer already ran for this terminal turn if the failure came after it; never call it twice.
        if (!progress.isTerminalObserved && terminal?.request == request.request && terminal.outcome != null) {
            val outcome = checkNotNull(terminal.outcome)
            observe("terminal") { observer.terminalTurn(request, active, turn, outcome, isHistoryCurrent = false) }
        }
        worktrees.failed(request.id.takeIf { progress.isIsolated }, request.request, active.ref, turn)
    }

    /** Returns whether recovery is done without an accepted turn or after closed observation was recorded. */
    private suspend fun recoverUnaccepted(request: StudioTurnRequest, progress: Progress): Boolean {
        if (progress.isObservationClosed) return true
        val active = progress.active
        val turn = progress.turn
        if (active?.state?.value == ActiveSessionState.Closed && turn == null && progress.delivery.isPending) {
            log.w { "Native handle closed during submission; retain pending worktree ownership" }
            progress.isObservationClosed = true
            if (progress.isIsolated) worktrees.submissionObservationLost(request.id, request.request, active.ref)
            request.onOutcome(TurnOutcome.Unknown)
            return true
        }
        if (active != null && turn != null) return false
        if (progress.isIsolated) worktrees.failed(request.id, request.request, active?.ref, turn)
        return true
    }

    /** Handle detachment is not native completion; never emit a terminal receipt or settle the worktree lease. */
    private suspend fun closedObservation(
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        progress: Progress,
        isObservationLost: Boolean = false,
    ) {
        log.w { "Native handle closed without a terminal outcome; retain worktree ownership" }
        progress.isObservationClosed = true
        if (!isObservationLost) {
            worktrees.observationLost(request.id.takeIf { progress.isIsolated }, request.request, active, turn)
        }
        tools.finishTurn(active.ref, turn)
        request.onOutcome(TurnOutcome.Unknown)
    }

    /** An unknown result keeps the runtime slot, approvals and merge lease until the process truly stops. */
    private suspend fun observeInterrupted(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        isIsolated: Boolean,
    ) {
        log.w { "Native cancellation is unconfirmed; retain ownership and continue observing" }
        worktrees.observationLost(request.id.takeIf { isIsolated }, request.request, active, turn)
        supervisorScope {
            val permissions = launch { active.state.collect { host.updatePermissions(request.id, it) } }
            try {
                active.state.first { it.isTerminalFor(turn) || it == ActiveSessionState.Closed }
            } finally {
                permissions.cancel()
            }
        }
    }

    private class Progress {
        var isIsolated = false
        var isPrepared = false
        var active: ActiveSession? = null
        var turn: TurnId? = null
        var isTerminal = false
        var isObservationClosed = false
        val delivery = StudioPromptDelivery()
        var isTerminalObserved = false
    }

    private companion object {
        const val RECOVERY_WAIT_MILLIS = 30_000L
    }
}
