package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
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
internal interface StudioTurnHost {
    suspend fun isWorktree(id: String): Boolean
    suspend fun openTurn(id: String, settings: RunSettings): ActiveSession
    suspend fun submitTurn(active: ActiveSession, request: StudioTurnRequest): TurnId
    suspend fun shouldStop(id: String): Boolean
    suspend fun requestStop(id: String, active: ActiveSession, turn: TurnId)
    suspend fun observeHistory(id: String, history: SessionHistory)
    suspend fun refreshHistory(id: String, history: SessionHistory)
    suspend fun updatePermissions(id: String, state: ActiveSessionState)
    suspend fun outcome(id: String, outcome: TurnOutcome?): RunOutcome
    suspend fun failedTurn(id: String, error: Exception): RunOutcome
}

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
) {
    override fun toString(): String = "StudioTurnRequest(id=$id, kind=$kind, attachments=${attachments.size})"
}

/** Confirms terminal native state and revokes tools before any worktree action lease is released. */
@Inject
internal class StudioTurnExecutor(private val worktrees: StudioWorktrees, private val tools: ProfileAgentTools) {
    private val log = Log.tag("StudioTurnExecutor")

    suspend fun execute(host: StudioTurnHost, request: StudioTurnRequest): RunOutcome {
        val progress = Progress()
        try {
            progress.isIsolated = host.isWorktree(request.id)
            if (progress.isIsolated) prepare(host, request, progress)
            val active = host.openTurn(request.id, request.settings)
            return runNative(host, request, active, progress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Studio execution failed" }
            if (progress.isPrepared || progress.active != null) recover(host, request, progress)
            return host.failedTurn(request.id, error)
        } finally {
            if (request.submission?.isCancelled == true && progress.isPrepared) {
                withContext(NonCancellable) {
                    worktrees.failed(request.id, request.request, session = null, turn = null)
                }
            }
        }
    }

    private suspend fun prepare(host: StudioTurnHost, request: StudioTurnRequest, progress: Progress) {
        worktrees.reconcile(request.id) { host.openTurn(request.id, request.settings) }
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
            val turn = host.submitTurn(active, request)
            progress.active = active
            progress.turn = turn
            notifyAccepted(request)
            if (progress.isIsolated) worktrees.accepted(request.id, request.request, active.ref, turn)
            if (host.shouldStop(request.id)) host.requestStop(request.id, active, turn)
            val terminal = active.state.first { it.isTerminalFor(turn) }
            progress.isTerminal = true
            tools.finishTurn(active.ref, turn)
            observation.cancelAndJoin()
            host.refreshHistory(request.id, history)
            val outcome = terminal.lastCompletedTurn()?.outcome ?: TurnOutcome.Unknown
            worktrees.settled(request.id.takeIf { progress.isIsolated }, request.request, active.ref, turn, outcome)
            host.outcome(request.id, outcome)
        } finally {
            observation.cancel()
            permissions.cancel()
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
        val active = progress.active
        val turn = progress.turn
        if (active == null || turn == null) {
            if (progress.isIsolated) worktrees.failed(request.id, request.request, active?.ref, turn)
            return
        }
        if (!progress.isTerminal) {
            host.requestStop(request.id, active, turn)
            val stopped = withTimeoutOrNull(RECOVERY_WAIT_MILLIS) { active.state.first { it.isTerminalFor(turn) } }
            if (stopped == null) observeInterrupted(host, request, active, turn, progress.isIsolated)
        }
        tools.finishTurn(active.ref, turn)
        worktrees.failed(request.id.takeIf { progress.isIsolated }, request.request, active.ref, turn)
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
                active.state.first { it.isTerminalFor(turn) }
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
    }

    private companion object {
        const val RECOVERY_WAIT_MILLIS = 30_000L
    }
}
