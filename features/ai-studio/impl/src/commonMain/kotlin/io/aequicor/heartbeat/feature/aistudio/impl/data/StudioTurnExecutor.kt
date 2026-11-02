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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The repository supplies persistence and native features; this executor owns a single accepted turn. */
internal interface StudioTurnHost : StudioSessionHost {
    /** Null only for hosts that have not opted into durable recovery publication. */
    val recoveryJournal: StudioRecoveryJournal? get() = null

    suspend fun isWorktree(id: String): Boolean

    /**
     * Journals a new send and updates its chat revision, only after recovery chooses Submit. Runs before worktree
     * preparation and environment carry. May replace settings and the submission gate, never request provenance.
     * If bookkeeping fails after journal admission, the host must revoke its newly created gate before throwing.
     */
    suspend fun prepareNewTurn(request: StudioTurnRequest): StudioTurnRequest

    /** Checks host stop routing before opening an older native turn; a new-send gate alone cannot prove this. */
    suspend fun requireNativeRecovery(request: StudioTurnRequest)

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

    /** Observes an existing native request; it must not fabricate acceptance of the recovery execution. */
    suspend fun adoptedTurn(request: StudioTurnRequest, active: ActiveSession, native: StudioNativeTurn) = Unit
    suspend fun terminalTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        turn: TurnId,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) = Unit
    suspend fun terminalAdoptedTurn(
        request: StudioTurnRequest,
        active: ActiveSession,
        native: StudioNativeTurn,
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
    /** Exact accepted native identity, before [onAccepted]; notification failure retains native ownership. */
    val onTurnAccepted: suspend (ActiveSession, TurnId) -> Unit = { _, _ -> },
    /** Compatibility outcome observer, invoked after mandatory native result persistence. */
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
    /** Original text from the human submission boundary, excluding generated plans and host continuations. */
    val userInstruction: String? = null,
    /** Executor-owned evidence recorded at the native send boundary, after preparation. */
    val delivery: StudioPromptDelivery? = null,
    /** Trusted producer declaration; it grants no logical owner or human capability. */
    val origin: StudioTurnOrigin = StudioTurnOrigin.UnknownService,
    /** Exact native evidence for new sends and adopted turns, without changing this execution's gate or id. */
    val onNativeTurn: suspend (ActiveSession, StudioNativeTurn) -> Unit = { _, _ -> },
    /** Durable virtual action source; the worktree machine validates it before preparing a new native run. */
    val continuationOf: RequestId? = null,
    /** Mandatory result sink; known outcomes include actual native identity, independently of checkpoint callbacks. */
    val onNativeOutcome: suspend (TurnOutcome, StudioRecoveryEvidence?) -> Unit = { _, _ -> },
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
        var admitted = request
        try {
            val preselected = request.submissionId.takeIf { it.isNotEmpty() }?.let(harnesses::preselected).orEmpty() +
                request.environment?.preselected.orEmpty()
            val recovered = preflightRecovery(host, request, progress)
            if (recovered == GraphRecovery.Submit) {
                if (request.recoveryRequests.isNotEmpty()) {
                    progress.isRecoverySubmitPrepared = true
                    prepareRecoverySubmit(host, request)
                }
                currentCoroutineContext().ensureActive()
                admitted = host.prepareNewTurn(request)
                request.requireAdmission(admitted)
            }
            progress.isIsolated = recovered == GraphRecovery.Submit &&
                request.compaction == null && host.isWorktree(request.id)
            if (progress.isIsolated) prepare(host, admitted, progress, preselected)
            val opened = if (recovered == GraphRecovery.Submit) {
                host.openEnvironmentSession(admitted, preselected, tools)
            } else {
                // Recovery observes the existing turn, never binds Q's outside routing to R.
                StudioEnvironmentSession(checkNotNull(progress.active), null)
            }
            val active = opened.active
            progress.active = active
            environment = opened.lease
            val boundRequest = admitted.copy(delivery = progress.delivery)
            return runNative(host, boundRequest, active, progress, recovered)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Studio execution failed" }
            if (progress.isPrepared || progress.active != null) recover(host, admitted, progress)
            return host.failedTurn(request.id, error)
        } finally {
            withContext(NonCancellable) {
                try {
                    finishRecoveryObservation(host, request, progress)
                } finally {
                    try {
                        finishExecution(admitted, environment, progress)
                    } finally {
                        finishUnsentRecovery(host, admitted, progress)
                    }
                }
            }
        }
    }

    /** Exact gate refusal or durable sender rejection closes only this delivery's new-send preparation. */
    private suspend fun finishUnsentRecovery(host: StudioTurnHost, request: StudioTurnRequest, progress: Progress) {
        if (!progress.isRecoverySubmitPrepared || progress.native != null) return
        observe("unsent recovery cleanup") {
            request.submission?.sealRecoveryNoSend()?.let { host.recoveryJournal?.noSend(it) }
        }
    }

    private suspend fun finishRecoveryObservation(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        progress: Progress,
    ) {
        if (!progress.isAdopted || progress.isRecoveryTerminalJournaled) return
        observe("lost recovery observation") {
            request.submission?.recoveryDelivery?.let { host.recoveryJournal?.unknown(it) }
        }
    }

    private suspend fun finishExecution(request: StudioTurnRequest, environment: AutoCloseable?, progress: Progress) {
        try {
            // An interrupted/uncertain native submission must never regain embedded tools.
            if (!progress.delivery.isPending || progress.isTerminal) environment?.close()
            if (request.submissionId.isNotEmpty()) harnesses.release(request.submissionId)
            if (request.submission?.isCancelled == true && progress.isPrepared) {
                withContext(NonCancellable) {
                    worktrees.failed(request.id, request.request, session = null, turn = null)
                }
            }
        } finally {
            withContext(NonCancellable) { (request.submission as? StudioHelperSubmission)?.cancel() }
        }
    }

    /** Inspect the saved native context before creating any new worktree ownership or outside routing. */
    private suspend fun preflightRecovery(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        progress: Progress,
    ): GraphRecovery {
        if (request.recoveryRequests.isEmpty()) return GraphRecovery.Submit
        check(request.origin is StudioTurnOrigin.GraphWork) { "Only proven graph work may recover native requests" }
        host.requireNativeRecovery(request)
        check(request.submission?.isNativeRetentionSupported != false) {
            "A journaled recovery request requires exact native stop routing before adoption"
        }
        check(request.submission?.isRecoveryObservationSupported != false) {
            "Recovery admission must retain observation during native inspection"
        }
        request.submission?.beginRecoveryObservation()
        currentCoroutineContext().ensureActive()
        val active = host.openRecoveryTurn(request)
        progress.active = active
        val decision = recoverGraphTurn(active, request)
        recordRecoveryDecision(host, request, active, progress, decision)
        return if (decision is GraphRecovery.Cancelled) {
            if (decision.evidence.request == request.request) {
                GraphRecovery.Completed(request.request, TurnOutcome.Cancelled)
            } else {
                GraphRecovery.Submit
            }
        } else {
            decision
        }
    }

    /** A refusing gate closes only this delivery; missing receipts are never used as proof. */
    private suspend fun prepareRecoverySubmit(host: StudioTurnHost, request: StudioTurnRequest) {
        var isAllowed = false
        try {
            request.submission?.prepareRecoverySubmit()
            isAllowed = true
        } finally {
            if (!isAllowed) {
                withContext(NonCancellable) {
                    observe("refused recovery delivery") {
                        request.submission?.sealRecoveryNoSend()?.let { host.recoveryJournal?.noSend(it) }
                    }
                }
            }
        }
        request.submission?.recoveryDelivery?.let { host.recoveryJournal?.submitting(it) }
    }

    /** Inspection decisions are saved before authorizing Submit or publishing a terminal-only result. */
    private suspend fun recordRecoveryDecision(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
        decision: GraphRecovery,
    ) {
        val journal = host.recoveryJournal ?: return
        val delivery = checkNotNull(request.submission?.recoveryDelivery) { "Recovery reservation is missing" }
        when (decision) {
            GraphRecovery.Submit -> journal.idle(delivery, active.ref)

            GraphRecovery.Unknown -> journal.unknown(delivery)

            is GraphRecovery.Cancelled -> journal.observed(delivery, decision.evidence)

            is GraphRecovery.Completed -> journal.observed(
                delivery,
                StudioRecoveryEvidence(active.ref, decision.request, outcome = decision.outcome),
            )

            is GraphRecovery.Adopt -> {
                // Ownership is retained before journal IO, including failed or lost write acknowledgements.
                progress.native = decision.native
                progress.isAdopted = true
                progress.retainNative(request.submission)
                recordAdopted(host, request, active, progress)
            }
        }
    }

    /** Journal failure cannot route an already accepted native turn through error-triggered Stop. */
    private suspend fun recordAdopted(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
        outcome: TurnOutcome? = null,
    ) {
        val journal = host.recoveryJournal ?: return
        val delivery = checkNotNull(request.submission?.recoveryDelivery)
        val native = checkNotNull(progress.native)
        try {
            progress.isStopRequested = progress.isStopRequested || journal.isStopRequested(delivery)
            val witness = journal.observed(
                delivery,
                StudioRecoveryEvidence(active.ref, native.request, native.turn, outcome),
            )
            progress.isStopRequested = progress.isStopRequested || witness.isStopRequested
            progress.isRecoveryJournalCurrent = true
            progress.isRecoveryTerminalJournaled = outcome != null && outcome != TurnOutcome.Unknown
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            progress.isRecoveryJournalCurrent = false
            log.e(error) { "Recovery journal acknowledgement failed; retaining native observation" }
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
        worktrees.send(
            WorktreeIntent.Public.RunStarted(
                request.id,
                request.request,
                request.kind,
                studioModelTarget(request.settings.modelId),
                continuationOf = request.continuationOf,
            ),
        )
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
        recovered: GraphRecovery,
    ): RunOutcome = supervisorScope {
        if (recovered == GraphRecovery.Unknown) {
            request.publishOutcome(TurnOutcome.Unknown)
            return@supervisorScope host.outcome(request.id, TurnOutcome.Unknown)
        }
        if (recovered is GraphRecovery.Completed) {
            request.publishOutcome(
                recovered.outcome,
                StudioRecoveryEvidence(active.ref, recovered.request, outcome = recovered.outcome),
            )
            return@supervisorScope host.outcome(request.id, recovered.outcome)
        }
        val history = active.features.requireFeature(SessionHistory)
        val observation = launch { host.observeHistory(request.id, history) }
        val permissions = launch { active.state.collect { host.updatePermissions(request.id, it) } }
        try {
            val native = submitOrAdopt(host, request, active, progress, recovered as? GraphRecovery.Adopt)
            val turn = native.turn
            observeAcceptance(host, request, active, progress, native)
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
            check(completed.request == native.request) { "Terminal turn belongs to another request" }
            val outcome = checkNotNull(completed.outcome) { "Terminal outcome is unavailable" }
            completeNative(host, request, active, progress, outcome)
        } finally {
            observation.cancel()
            permissions.cancel()
        }
    }

    private suspend fun completeNative(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
        outcome: TurnOutcome,
    ): RunOutcome {
        val native = checkNotNull(progress.native)
        progress.isTerminalObserved = true
        if (progress.isAdopted) recordAdopted(host, request, active, progress, outcome)
        observeTerminal(request, active, progress, outcome, isHistoryCurrent = true)
        // The recovery envelope has no authority to trigger follow-up work for the observed native request.
        if (!progress.isAdopted) observe("settlement") { settlement.settled(request, active, outcome) }
        val reported = if (progress.isRecoveryJournalCurrent) outcome else TurnOutcome.Unknown
        request.publishOutcome(
            reported,
            StudioRecoveryEvidence(active.ref, native.request, native.turn, reported),
        )
        worktrees.settled(request.id.takeIf { progress.isIsolated }, native.request, active.ref, native.turn, outcome)
        return host.outcome(request.id, reported)
    }

    private suspend fun observeAcceptance(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
        native: StudioNativeTurn,
    ) {
        observe("acceptance") {
            if (progress.isAdopted) {
                observer.adoptedTurn(request, active, native)
            } else {
                observer.acceptedTurn(request, active, native.turn)
            }
        }
        if (progress.isRecoveryJournalCurrent) {
            observe("native checkpoint") { request.onNativeTurn(active, native) }
        }
        if (!progress.isAdopted) {
            observe("accepted checkpoint") { request.onTurnAccepted(active, native.turn) }
            notifyAccepted(request)
        }
        if (progress.isIsolated) worktrees.accepted(request.id, request.request, active.ref, native.turn)
        if (progress.isStopRequested || host.shouldStop(request.id)) host.requestStop(request.id, active, native.turn)
    }

    private suspend fun submitOrAdopt(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
        recovered: GraphRecovery.Adopt?,
    ): StudioNativeTurn = if (recovered != null) {
        check(active.state.value.matches(recovered.native)) { "Recovered native identity changed after inspection" }
        progress.native = recovered.native
        progress.isAdopted = true
        progress.retainNative(request.submission)
        recovered.native
    } else {
        connectHarnesses(request, active)
        StudioNativeTurn(request.request, host.submitTurn(active, request)).also { progress.native = it }
    }

    /** Only a confirmed stopped prior turn permits an automatic continuation in the same chat. */
    private suspend fun recoverGraphTurn(active: ActiveSession, request: StudioTurnRequest): GraphRecovery {
        if (request.recoveryRequests.isEmpty()) return GraphRecovery.Submit
        val inspector = (active.features.resolve(RestoresSessionTurns) as? FeatureAccess.Available)?.feature
        if (inspector != null) return inspectGraphTurn(active, request, inspector.inspect(request.recoveryCheckpoint))
        if (active.state.value is ActiveSessionState.Unavailable) {
            active.features.requireFeature(ReconcilesSession).synchronize()
        }
        return rememberedGraphTurn(active, active.state.value, request)
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
                TurnOutcome.Cancelled -> GraphRecovery.Cancelled(
                    StudioRecoveryEvidence(active.ref, inspection.request, outcome = outcome),
                )

                TurnOutcome.Unknown -> GraphRecovery.Unknown

                is TurnOutcome.Failed, TurnOutcome.Completed -> GraphRecovery.Completed(inspection.request, outcome)

                null -> {
                    val state = withTimeoutOrNull(RECOVERY_WAIT_MILLIS) {
                        active.state.first {
                            it.activeTurn()?.request == inspection.request ||
                                it.lastCompletedTurn()?.request == inspection.request
                        }
                    }
                    state?.let { rememberedGraphTurn(active, it, request) } ?: GraphRecovery.Unknown
                }
            }
        }
    }

    private fun rememberedGraphTurn(
        active: ActiveSession,
        state: ActiveSessionState,
        request: StudioTurnRequest,
    ): GraphRecovery {
        if (state is ActiveSessionState.Submitting) return GraphRecovery.Unknown
        val running = state.activeTurn()
        if (running != null) {
            val actual = running.request ?: return GraphRecovery.Unknown
            return if (state !is ActiveSessionState.Unavailable && actual in request.recoveryRequests) {
                GraphRecovery.Adopt(StudioNativeTurn(actual, running.id))
            } else {
                GraphRecovery.Unknown
            }
        }
        val finished = state.lastCompletedTurn()
        val actual = finished?.request ?: return GraphRecovery.Unknown
        return if (actual in request.recoveryRequests) {
            when (finished.outcome) {
                TurnOutcome.Completed, is TurnOutcome.Failed ->
                    GraphRecovery.Adopt(StudioNativeTurn(actual, finished.id))

                TurnOutcome.Cancelled -> GraphRecovery.Cancelled(
                    StudioRecoveryEvidence(active.ref, actual, finished.id, TurnOutcome.Cancelled),
                )

                TurnOutcome.Unknown, null -> GraphRecovery.Unknown
            }
        } else {
            GraphRecovery.Unknown
        }
    }

    private sealed interface GraphRecovery {
        data object Submit : GraphRecovery
        data object Unknown : GraphRecovery
        data class Adopt(val native: StudioNativeTurn) : GraphRecovery
        data class Cancelled(val evidence: StudioRecoveryEvidence) : GraphRecovery
        data class Completed(val request: RequestId, val outcome: TurnOutcome) : GraphRecovery
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
        val native = checkNotNull(progress.native)
        val turn = native.turn
        if (!progress.isTerminal) {
            if (active.state.value != ActiveSessionState.Closed) host.requestStop(request.id, active, turn)
            val stopped = withTimeoutOrNull(RECOVERY_WAIT_MILLIS) {
                active.state.first { it.isTerminalFor(turn) || it == ActiveSessionState.Closed }
            }
            if (stopped == null) observeInterrupted(host, request, active, native, progress.isIsolated)
            if (active.state.value == ActiveSessionState.Closed) {
                closedObservation(request, active, turn, progress, isObservationLost = stopped == null)
                return
            }
        }
        tools.finishTurn(active.ref, turn)
        progress.isTerminal = true
        val terminal = active.state.value.takeIf { it.isTerminalFor(turn) }?.lastCompletedTurn()
        // The observer already ran for this terminal turn if the failure came after it; never call it twice.
        if (!progress.isTerminalObserved && terminal?.request == native.request && terminal.outcome != null) {
            val outcome = checkNotNull(terminal.outcome)
            if (progress.isAdopted) recordAdopted(host, request, active, progress, outcome)
            observeTerminal(request, active, progress, outcome, isHistoryCurrent = false)
        }
        worktrees.failed(request.id.takeIf { progress.isIsolated }, native.request, active.ref, turn)
    }

    /** Returns whether recovery is done without an accepted turn or after closed observation was recorded. */
    private suspend fun recoverUnaccepted(request: StudioTurnRequest, progress: Progress): Boolean {
        if (progress.isObservationClosed) return true
        val active = progress.active
        val turn = progress.native?.turn
        if (active?.state?.value == ActiveSessionState.Closed && turn == null && progress.delivery.isPending) {
            log.w { "Native handle closed during submission; retain pending worktree ownership" }
            progress.isObservationClosed = true
            if (progress.isIsolated) worktrees.submissionObservationLost(request.id, request.request, active.ref)
            request.publishOutcome(TurnOutcome.Unknown)
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
            val native = checkNotNull(progress.native)
            worktrees.observationLost(request.id.takeIf { progress.isIsolated }, native.request, active, turn)
        }
        tools.finishTurn(active.ref, turn)
        val native = checkNotNull(progress.native)
        request.publishOutcome(
            TurnOutcome.Unknown,
            StudioRecoveryEvidence(active.ref, native.request, native.turn, TurnOutcome.Unknown),
        )
    }

    /** An unknown result keeps the runtime slot, approvals and merge lease until the process truly stops. */
    private suspend fun observeInterrupted(
        host: StudioTurnHost,
        request: StudioTurnRequest,
        active: ActiveSession,
        native: StudioNativeTurn,
        isIsolated: Boolean,
    ) {
        log.w { "Native cancellation is unconfirmed; retain ownership and continue observing" }
        worktrees.observationLost(request.id.takeIf { isIsolated }, native.request, active, native.turn)
        supervisorScope {
            val permissions = launch { active.state.collect { host.updatePermissions(request.id, it) } }
            try {
                active.state.first { it.isTerminalFor(native.turn) || it == ActiveSessionState.Closed }
            } finally {
                permissions.cancel()
            }
        }
    }

    private suspend fun observeTerminal(
        request: StudioTurnRequest,
        active: ActiveSession,
        progress: Progress,
        outcome: TurnOutcome,
        isHistoryCurrent: Boolean,
    ) {
        val native = checkNotNull(progress.native)
        observe("terminal") {
            if (progress.isAdopted) {
                observer.terminalAdoptedTurn(request, active, native, outcome, isHistoryCurrent)
            } else {
                observer.terminalTurn(request, active, native.turn, outcome, isHistoryCurrent)
            }
        }
    }

    private class Progress {
        var isIsolated = false
        var isPrepared = false
        var active: ActiveSession? = null
        var native: StudioNativeTurn? = null
        var isAdopted = false
        var isStopRequested = false
        var isTerminal = false
        var isObservationClosed = false
        val delivery = StudioPromptDelivery()
        var isTerminalObserved = false
        var isRecoveryJournalCurrent = true
        var isRecoveryTerminalJournaled = false
        var isRecoverySubmitPrepared = false
        private var isNativeRetained = false

        suspend fun retainNative(submission: StudioSubmissionGate?) {
            if (isNativeRetained) return
            isStopRequested = submission?.retainNative() == true
            isNativeRetained = true
        }
    }

    private companion object {
        const val RECOVERY_WAIT_MILLIS = 30_000L
    }
}

/** A result sink failure prevents a completion acknowledgement; optional checkpoint failures cannot erase identity. */
private suspend fun StudioTurnRequest.publishOutcome(outcome: TurnOutcome, evidence: StudioRecoveryEvidence? = null) {
    onNativeOutcome(outcome, evidence)
    onOutcome(outcome)
}
