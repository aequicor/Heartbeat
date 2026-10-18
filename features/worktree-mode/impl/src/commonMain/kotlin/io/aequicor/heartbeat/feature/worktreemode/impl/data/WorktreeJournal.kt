package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.buildUpdated
import io.aequicor.heartbeat.feature.worktreemode.api.canChooseAction
import io.aequicor.heartbeat.feature.worktreemode.api.failed
import io.aequicor.heartbeat.feature.worktreemode.api.hasActiveBuilds
import io.aequicor.heartbeat.feature.worktreemode.api.hasActiveRun
import io.aequicor.heartbeat.feature.worktreemode.api.operationFailed
import io.aequicor.heartbeat.feature.worktreemode.api.preflightRecovered
import io.aequicor.heartbeat.feature.worktreemode.api.prepared
import io.aequicor.heartbeat.feature.worktreemode.api.reconciled
import io.aequicor.heartbeat.feature.worktreemode.api.runPrepared
import io.aequicor.heartbeat.feature.worktreemode.api.sealed
import io.aequicor.heartbeat.feature.worktreemode.api.trackingMain
import io.aequicor.heartbeat.feature.worktreemode.api.transition
import io.aequicor.heartbeat.feature.worktreemode.api.virtualChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

internal val WorktreeJournalSpec = KeyValueSpec("worktree_mode_journal")
private val JournalKey = stringKey("records")
private val terminalBuildPhases = setOf(
    WorktreeBuildPhase.Completed,
    WorktreeBuildPhase.Cancelled,
    WorktreeBuildPhase.Failed,
)

/** Durable side-effect journal. Corrupt storage fails closed and never becomes an empty worktree registry. */
@Inject
@SingleIn(ProfileScope::class)
internal class WorktreeJournal(
    @ForScope(ProfileScope::class) stores: DataStores,
    internal val git: WorktreeGit,
    private val builds: WorktreeBuildCoordinator,
    private val workspaces: LocalWorkspaces,
    private val toggles: FeatureToggles,
    private val projectSettings: Lazy<ProjectBuildSettings> = lazy { error("ProjectBuildSettingsUnavailable") },
    private val facade: Lazy<EngineFacade>? = null,
    sourceBarriers: VirtualSourceBarrier = VirtualSourceBarriers(),
) {
    private val log = Log.tag("WorktreeJournal")
    private val store = stores.keyValue(WorktreeJournalSpec)
    private val mutex = Mutex()
    private var records: Map<String, WorktreeRecord>? = null
    private val preparations = mutableMapOf<String, Mutex>()
    private val upkeep = SandboxUpkeep(git)
    private val actions = WorktreeActionRunner(git, builds, ::record, ::update)
    private val virtual = VirtualTaskLifecycle(
        git,
        builds,
        facade,
        sourceBarriers,
        access = VirtualTaskAccess(
            all = { mutex.withLock { read().values.toList() } },
            find = ::record,
            change = ::update,
            settle = { commitTransition(it) },
            finishTask = ::finishVirtual,
        ),
    )

    suspend fun load(): Map<String, WorktreeTask> {
        val saved = mutex.withLock { read() }
        saved.values.forEach { record ->
            reconcile(record.task.chatId, restart = true)
        }
        return mutex.withLock { read().mapValues { it.value.task } }
    }

    suspend fun find(workspace: WorkspaceRef?): WorktreeTask? = mutex.withLock {
        if (workspace == null || records == null) return@withLock null
        read().values.firstOrNull { it.task.executionWorkspace == workspace }?.task
    }

    suspend fun taskProjection(chatId: String): WorktreeTask? = record(chatId)?.task

    /** Provisioned sandbox tasks; their repositories own build slots. */
    suspend fun sandboxRecords(): List<WorktreeRecord> = mutex.withLock {
        read().values.filter { it.task.isSandbox && it.provisioning.isNotBlank() }
    }

    /** Captures the concrete immutable command for the facade's single authorization gate. */
    suspend fun buildPreview(context: AgentToolContext, command: String): BuildExecution? {
        val task = authenticated(context) ?: return null
        val current = record(task.chatId) ?: return null
        if (current.task.buildPlan == null) return null
        val execution = git.build(current, "approval", command)
        return if (current.task.virtualSandbox != null) {
            val configuration = projectSettings.value.get(current.commonDirectory)
            check(configuration.plan == current.task.buildPlan) { "ProjectBuildConfigurationChanged" }
            execution.copy(projectConfigurationRevision = configuration.revision)
        } else {
            execution
        }
    }

    suspend fun observeWorkers(emit: suspend (WorktreeTask) -> Unit) {
        val restored = mutex.withLock { read().values.toList() }
        restored.forEach { reclaimOutputs(it.task.chatId) }
        pruneSandboxSnapshots(git, restored)
        val saved = mutex.withLock { read().values.toList() }
        coroutineScope {
            launch { observeRecords(saved, emit) }
            virtual.observe(emit)
        }
    }

    private suspend fun observeRecords(saved: List<WorktreeRecord>, emit: suspend (WorktreeTask) -> Unit) =
        coroutineScope {
            saved.flatMap { record ->
                record.jobs.values.filter {
                    record.task.builds[it.id]?.phase !in terminalBuildPhases
                }.map { record to it }
            }.sortedBy { it.second.enqueueOrder }.forEach { (record, execution) ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val projection = coroutineContext
                    val result = builds.execute(execution, isReattachment = true) { operation ->
                        project(projection) {
                            buildUpdate(record.task.chatId, operation)?.let { emit(it.task) }
                        }
                    }
                    buildUpdate(record.task.chatId, result)?.let { emit(it.task) }
                }
            }
        }

    /** The adapter supplies this identity; argument JSON can never select another chat or native turn. */
    suspend fun authenticated(context: AgentToolContext): WorktreeTask? {
        val task = mutex.withLock {
            read().values.firstOrNull {
                it.task.executionWorkspace == context.workspace &&
                    (it.task.isIsolated || it.mainSession == context.session)
            }?.task
        } ?: return null
        val run = task.run ?: return null
        if (run.outcome != null || !run.isPrepared) return null
        if (run.session == context.session && run.turn == context.turn) {
            if (context.target != null) update(task.chatId) { it.copy(runTarget = context.target) }
            return task
        }
        // A tool call itself proves acceptance and may reach us before the submit waiter gets its acknowledgement.
        if (run.session == null && run.turn == null && context.request == run.request) {
            return update(task.chatId) {
                it.copy(
                    runTarget = context.target,
                    task = it.task.transition(
                        WorktreeIntent.Public.RunAccepted(task.chatId, run.request, context.session, context.turn),
                    ),
                )
            }?.task
        }
        return null
    }

    suspend fun apply(command: WorktreeIntent.Public, emit: suspend (WorktreeTask) -> Unit) {
        applyCommand(command, emit)
        command.chatId()?.let { reclaimOutputs(it) }
    }

    /** See [reclaimRetainedSandbox]; new turns and cleanup share this lock independently of completion choices. */
    private suspend fun reclaimOutputs(chatId: String) {
        val preparation = mutex.withLock { preparations.getOrPut(chatId) { Mutex() } }
        upkeep.reclaim(chatId, preparation, { record(chatId) }) { markReclaimed(chatId, it) }
    }

    /** Path-side bookkeeping only: the visible task and its revision stay unchanged. */
    private suspend fun markReclaimed(chatId: String, isReclaimed: Boolean) = mutex.withLock {
        val saved = read()
        val previous = saved[chatId] ?: return@withLock
        val isCurrent = !isReclaimed || previous.task.phase == WorktreePhase.Retained
        if (previous.isOutputsReclaimed != isReclaimed && isCurrent) {
            write(saved + (chatId to previous.copy(isOutputsReclaimed = isReclaimed)))
        }
    }

    private suspend fun applyCommand(command: WorktreeIntent.Public, emit: suspend (WorktreeTask) -> Unit) {
        when (command) {
            is WorktreeIntent.Public.Prepare -> prepare(command, emit)

            is WorktreeIntent.Public.TrackMainSession -> trackMain(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.Recheck -> recheckCommand(command, emit)

            is WorktreeIntent.Public.ChooseAction -> choose(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.ProposeBuildPlan -> proposeBuildPlan(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.RunBuild -> runBuild(command, emit)

            is WorktreeIntent.Public.CancelBuild -> cancelBuild(command)?.let { emit(it.task) }

            WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
            is WorktreeIntent.Public.RunStarted, is WorktreeIntent.Public.RunSettled,
            is WorktreeIntent.Public.RunRejected, is WorktreeIntent.Public.RunAccepted,
            is WorktreeIntent.Public.RunSubmissionObservationLost, is WorktreeIntent.Public.RunSubmissionReconciled,
            is WorktreeIntent.Public.RunObservationLost, is WorktreeIntent.Public.RunAdopted,
            is WorktreeIntent.Public.Seal, is WorktreeIntent.Public.TaskCompleteSignaled,
            is WorktreeIntent.Public.ActionDelivered, is WorktreeIntent.Public.ActionDeliveryFailed,
            is WorktreeIntent.Public.ApproveBuildPlan,
            -> applyLifecycle(command, emit)
        }
    }

    private suspend fun recheck(chatId: String, emit: suspend (WorktreeTask) -> Unit) {
        val reconciled = reconcile(chatId) ?: return
        emit(reconciled.task)
        observeRecords(listOf(reconciled), emit)
    }

    private suspend fun applyLifecycle(command: WorktreeIntent.Public, emit: suspend (WorktreeTask) -> Unit) {
        when (command) {
            is WorktreeIntent.Public.RunStarted -> beginRun(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.RunSettled -> settleRun(command, emit)

            is WorktreeIntent.Public.RunRejected -> rejectRun(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.RunSubmissionReconciled -> reconcileSubmission(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.ActionDeliveryFailed -> deliveryFailed(command)?.let { emit(it.task) }

            is WorktreeIntent.Public.TaskCompleteSignaled -> signalCompletion(command, emit)

            is WorktreeIntent.Public.Seal -> seal(command, emit)

            // Start and RetryLoad have no task: committing them finds no chat and changes nothing.
            WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
            is WorktreeIntent.Public.Prepare, is WorktreeIntent.Public.TrackMainSession,
            is WorktreeIntent.Public.Recheck, is WorktreeIntent.Public.ChooseAction,
            is WorktreeIntent.Public.ProposeBuildPlan, is WorktreeIntent.Public.RunBuild,
            is WorktreeIntent.Public.CancelBuild, is WorktreeIntent.Public.RunAccepted,
            is WorktreeIntent.Public.RunSubmissionObservationLost, is WorktreeIntent.Public.RunObservationLost,
            is WorktreeIntent.Public.RunAdopted, is WorktreeIntent.Public.ActionDelivered,
            is WorktreeIntent.Public.ApproveBuildPlan,
            -> commitTransition(command)?.let { emit(it.task) }
        }
    }

    private suspend fun recheckCommand(command: WorktreeIntent.Public.Recheck, emit: suspend (WorktreeTask) -> Unit) {
        record(command.chatId)?.takeIf { it.task.virtualSandbox != null }?.let { selected ->
            virtual.refresh(selected, command.isResume, command.resolveConflicts)
            virtual.related(selected.commonDirectory).forEach { emit(it.task) }
        } ?: recheck(command.chatId, emit)
    }

    private suspend fun signalCompletion(
        command: WorktreeIntent.Public.TaskCompleteSignaled,
        emit: suspend (WorktreeTask) -> Unit,
    ) {
        val changed = commitTransition(command) ?: return
        emit(actions.verify(changed).task)
    }

    private suspend fun commitTransition(command: WorktreeIntent.Public): WorktreeRecord? {
        val chatId = command.chatId() ?: return null
        return update(chatId) { it.copy(task = it.task.transition(command)) }
    }

    private suspend fun proposeBuildPlan(command: WorktreeIntent.Public.ProposeBuildPlan): WorktreeRecord? {
        requireAvailable()
        val existing = record(command.chatId) ?: return null
        return if (command.expectedRun != null && !existing.task.hasActiveRun(command.expectedRun)) {
            existing
        } else {
            check(!existing.task.hasActiveBuilds()) { "BuildConfigurationBusy" }
            git.validatePlan(existing, command.plan)
            if (existing.task.virtualSandbox != null) {
                projectSettings.value.configure(
                    existing.commonDirectory,
                    command.plan,
                )
            }
            commitTransition(command)
        }
    }

    private suspend fun beginRun(command: WorktreeIntent.Public.RunStarted): WorktreeRecord? =
        upkeep.prepareRun(command.chatId) {
            requireAvailable()
            val captured = update(command.chatId) {
                if (it.task.virtualSandbox == null) it.startingRun(command) else it
            } ?: return@prepareRun null
            val pending = if (captured.task.virtualSandbox != null) {
                val engine = facade?.value?.engines?.state?.value?.firstOrNull {
                    it.descriptor.id == command.target?.engine
                }
                check(engine?.descriptor?.isVirtualWorkspaceSupported != false) { "VirtualWorkspaceUnsupported" }
                val checked = virtual.refresh(captured, explicit = false)
                check(checked?.task?.virtualSandbox?.let { it.isReady || it.isResolving } == true) { "SandboxPaused" }
                update(command.chatId) { it.startingRun(command) } ?: return@prepareRun null
            } else {
                captured
            }
            if (pending.task.run?.request != command.request || pending.task.run?.isPrepared == true) {
                pending
            } else {
                check(pending.task.builds.values.none { it.phase == WorktreeBuildPhase.Unknown }) {
                    "WorkerOutcomeUnknown"
                }
                check(git.exists(pending)) { "WorktreeUnavailable" }
                update(command.chatId) { it.copy(task = it.task.runPrepared(command.request)) }
            }
        }

    /** Serialized with run preparation, so a new native turn cannot start between the check and the capture. */
    private suspend fun seal(command: WorktreeIntent.Public.Seal, emit: suspend (WorktreeTask) -> Unit) {
        val result = upkeep.prepareRun(command.chatId) {
            val captured = sealCheckout(git, command, { record(command.chatId) }) { seal ->
                update(command.chatId) {
                    val proofs = if (it.task.pinnedBaseCommit != null && seal.commit != null) {
                        check(it.resultProofs.size < MAX_RESULT_PROOFS || seal.operation in it.resultProofs) {
                            "ResultProofCapacity"
                        }
                        it.resultProofs + (seal.operation to checkNotNull(seal.commit))
                    } else {
                        it.resultProofs
                    }
                    // A successful revalidation is a new acknowledgement even when its commit is unchanged.
                    it.copy(task = it.task.sealed(seal).copy(revision = it.task.revision + 1), resultProofs = proofs)
                }
            } ?: return@prepareRun null
            val state = if (command.handoff && captured.task.virtualSandbox != null) {
                git.handoffVirtual(captured)
            } else {
                null
            }
            if (state != null) {
                update(command.chatId) { it.copy(task = it.task.virtualChanged(state)) }
            } else {
                captured
            }
        }
        result?.let { emit(it.task) }
    }

    private suspend fun cancelBuild(command: WorktreeIntent.Public.CancelBuild): WorktreeRecord? {
        val owned = record(command.chatId)?.task?.builds?.get(command.operation) ?: return null
        return if (builds.cancel(command.operation)) {
            buildUpdate(command.chatId, owned.copy(phase = WorktreeBuildPhase.Cancelled, queuePosition = null))
        } else {
            null
        }
    }

    private suspend fun settleRun(command: WorktreeIntent.Public.RunSettled, emit: suspend (WorktreeTask) -> Unit) {
        val captured = upkeep.prepareRun(command.chatId) { settleCaptured(command) } ?: return
        emit(captured.task)
        if (captured.task.run?.request == command.request && command.outcome == TurnOutcome.Cancelled) {
            captured.task.builds.values.filter { it.phase !in terminalBuildPhases }.forEach { builds.cancel(it.id) }
        }
    }

    private suspend fun settleCaptured(command: WorktreeIntent.Public.RunSettled): WorktreeRecord? {
        val changed = commitTransition(command) ?: return null
        val verified = actions.verify(changed)
        val isMatching = verified.task.run?.let { it.request == command.request && it.turn == command.turn } == true
        val isCompletedMerge =
            verified.task.run?.let { it.kind == WorktreeRunKind.Merge && it.outcome != null } == true
        val released = if (isMatching && isCompletedMerge && verified.mergeLease?.id == command.request.value) {
            actions.releaseMerge(verified)
        } else {
            verified
        }
        val ready = virtual.finish(released)
        val captured = virtual.captureChild(ready, isMatching && command.outcome == TurnOutcome.Completed)
        return captured
    }

    private suspend fun finishVirtual(chatId: String): WorktreeRecord? = upkeep.prepareRun(chatId) {
        record(chatId)?.let { virtual.finish(it) }
    }

    private suspend fun rejectRun(command: WorktreeIntent.Public.RunRejected): WorktreeRecord? {
        val before = record(command.chatId)
        val changed = commitTransition(command) ?: return null
        val isRejected = before?.task?.run?.let { it.request == command.request && it.turn == null } == true
        return if (isRejected && before.mergeLease?.id == command.request.value && changed.task.run == null) {
            actions.releaseMerge(changed)
        } else {
            changed
        }
    }

    private suspend fun reconcileSubmission(command: WorktreeIntent.Public.RunSubmissionReconciled): WorktreeRecord? {
        val before = record(command.chatId)
        val changed = commitTransition(command) ?: return null
        val isCleared = before?.task?.run?.request == command.request && changed.task.run == null
        return if (isCleared && before.mergeLease?.id == command.request.value) {
            actions.releaseMerge(
                changed,
            )
        } else {
            changed
        }
    }

    private suspend fun deliveryFailed(command: WorktreeIntent.Public.ActionDeliveryFailed): WorktreeRecord? {
        var isRejected = false
        val changed = update(command.chatId) {
            val next = it.task.transition(command)
            isRejected = next != it.task
            it.copy(task = next)
        } ?: return null
        return if (isRejected && changed.mergeLease?.id == command.operation) {
            actions.releaseMerge(changed)
        } else {
            changed
        }
    }
    suspend fun failed(command: WorktreeIntent.Public, reason: String): WorktreeTask? {
        val chatId = command.chatId() ?: return null
        if (command is WorktreeIntent.Public.Prepare) {
            val isSandbox = isSandbox(command)
            mutex.withLock {
                val saved = read()
                if (chatId !in saved) {
                    val task = WorktreeTask(chatId, command.project, isSandbox = isSandbox).failed(reason)
                    write(saved + (chatId to WorktreeRecord(task, "", "", "", "")))
                }
            }
        }
        val changed = update(chatId) { it.copy(task = it.task.operationFailed(command, reason)) } ?: return null
        val isRejectedBeforeAcceptance = command is WorktreeIntent.Public.RunStarted &&
            changed.task.run?.request == command.request && changed.task.run?.turn == null &&
            changed.mergeLease?.id == command.request.value
        return if (command is WorktreeIntent.Public.ChooseAction || isRejectedBeforeAcceptance) {
            actions.releaseMerge(changed).task
        } else {
            changed.task
        }
    }

    private suspend fun prepare(command: WorktreeIntent.Public.Prepare, emit: suspend (WorktreeTask) -> Unit) {
        val preparation = mutex.withLock { preparations.getOrPut(command.chatId) { Mutex() } }
        preparation.withLock { WorktreePreparation(this, git, workspaces, toggles).execute(command, emit) }
    }

    internal suspend fun provision(record: WorktreeRecord, workspace: WorkspaceRef? = null): WorktreeTask? =
        if (workspace == null) {
            mutex.withLock { write(read() + (record.task.chatId to record)) }
            record.task
        } else {
            update(record.task.chatId) { it.copy(task = it.task.prepared(workspace)) }?.task
        }

    private suspend fun trackMain(command: WorktreeIntent.Public.TrackMainSession): WorktreeRecord? {
        val existing = mutex.withLock {
            read().values.firstOrNull {
                it.mainSession == command.session &&
                    it.task.project == command.workspace
            }
        }
        if (existing != null) {
            requireAvailable()
            return update(existing.task.chatId) { it.copy(task = it.task.trackingMain(command)) }
        }
        requireEnabled()
        val source = checkNotNull(workspaces.resolve(command.workspace)) { "ProjectUnavailable" }
        val plan = git.trackMain(source)
        val id = Uuid.random().toString()
        val task = WorktreeTask(
            id,
            command.workspace,
            executionWorkspace = command.workspace,
            sourceBranch = plan.sourceBranch,
            branch = plan.branch,
            baseCommit = plan.baseCommit,
            isIsolated = false,
        ).trackingMain(command)
        val record = WorktreeRecord(
            task,
            plan.sourceDirectory,
            plan.directory,
            plan.commonDirectory,
            id,
            mainSession = command.session,
        )
        return mutex.withLock {
            val current = read().values.firstOrNull {
                it.mainSession == command.session &&
                    it.task.project == command.workspace
            }
            if (current != null) return@withLock current
            write(read() + (id to record))
            record
        }
    }

    private suspend fun choose(command: WorktreeIntent.Public.ChooseAction): WorktreeRecord? {
        val choice = mutex.withLock { preparations.getOrPut(command.chatId) { Mutex() } }
        return choice.withLock { chooseLocked(command) }
    }

    private suspend fun chooseLocked(command: WorktreeIntent.Public.ChooseAction): WorktreeRecord? {
        requireAvailable()
        val existing = record(command.chatId) ?: return null
        val isSimpleChoice = command.action == WorktreeAction.Leave ||
            (command.action == WorktreeAction.Refine && command.refinement.isNullOrBlank())
        return when {
            !existing.task.canChooseAction() -> existing
            isSimpleChoice -> commitTransition(command)
            else -> actions.prepare(command, existing)
        }
    }

    private suspend fun runBuild(command: WorktreeIntent.Public.RunBuild, emit: suspend (WorktreeTask) -> Unit) {
        requireAvailable()
        val current = record(command.chatId) ?: return
        check(current.mergeLease == null) { "MergeInProgress" }
        check(current.task.builds.values.none { it.phase == WorktreeBuildPhase.Unknown }) { "WorkerOutcomeUnknown" }
        if (command.operation in current.task.builds) {
            emit(current.task)
            return
        }
        check(current.task.isBuildApproved && current.task.buildPlan != null) { "NeedsConfiguration" }
        check(
            current.task.buildConfigurationRevision == command.expectedConfigurationRevision,
        ) { "BuildConfigurationChanged" }
        check(current.task.run?.outcome == null && current.task.run?.turn != null) { "TurnUnavailable" }
        check(current.task.hasActiveRun(command.expectedRun)) { "TurnUnavailable" }
        val execution = prepareBuild(current, command)
        val queued = try {
            queueBuild(command, execution)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (execution.sandbox != null) git.discardSnapshot(current, command.operation)
            throw error
        }
        queued?.let { emit(it.task) }
        val accepted = checkNotNull(queued?.jobs?.get(command.operation))
        val projection = coroutineContext
        val result = builds.execute(accepted) { build ->
            project(projection) { buildUpdate(command.chatId, build)?.let { emit(it.task) } }
        }
        buildUpdate(command.chatId, result)?.let { emit(it.task) }
    }

    private suspend fun prepareBuild(current: WorktreeRecord, command: WorktreeIntent.Public.RunBuild): BuildExecution {
        val projectConfiguration = if (current.task.virtualSandbox != null) {
            projectSettings.value.get(current.commonDirectory).also {
                check(it.revision == command.expectedProjectRevision && it.plan == current.task.buildPlan) {
                    "ProjectBuildConfigurationChanged"
                }
            }
        } else {
            null
        }
        return git.build(current, command.operation, command.command).let { prepared ->
            val sandbox = prepared.sandbox ?: return@let prepared
            val snapshot = git.snapshot(current, command.operation)
            if (current.task.virtualSandbox != null) {
                check(
                    snapshot == command.expectedSnapshot,
                ) { "SandboxRevisionChanged" }
            }
            prepared.copy(
                sandbox = sandbox.copy(snapshot = snapshot),
                projectConfigurationRevision = projectConfiguration?.revision ?: 0,
            )
        }
    }

    private suspend fun queueBuild(command: WorktreeIntent.Public.RunBuild, execution: BuildExecution) =
        update(command.chatId) {
            check(
                it.task.buildConfigurationRevision == command.expectedConfigurationRevision,
            ) { "BuildConfigurationChanged" }
            check(it.task.hasActiveRun(command.expectedRun)) {
                "TurnUnavailable"
            }
            val order = checkNotNull(records).values.flatMap { record -> record.jobs.values }
                .maxOfOrNull { job -> job.enqueueOrder } ?: 0
            val immutable = execution.copy(enqueueOrder = order + 1)
            it.copy(
                task = it.task.buildUpdated(
                    WorktreeBuildOperation(
                        command.operation,
                        command.command,
                        configurationRevision = execution.configurationRevision,
                    ),
                ),
                jobs = it.jobs + (command.operation to immutable),
            )
        }

    private suspend fun buildUpdate(chatId: String, build: WorktreeBuildOperation): WorktreeRecord? = update(chatId) {
        if (build.id !in it.task.builds) return@update it
        it.copy(task = it.task.buildUpdated(build))
    }

    private suspend fun reconcile(chatId: String, restart: Boolean = false): WorktreeRecord? {
        val current = record(chatId) ?: return null
        if (current.provisioning.isBlank()) return current
        val isPresent = git.isAvailable && git.exists(current)
        val workspace = if (isPresent && current.task.executionWorkspace == null) {
            git.virtualWorkspace(current) ?: workspaces.registerManaged(current.directory).ref
        } else {
            null
        }
        var task = current.task.reconciled(isPresent, workspace, restart)
        if (!restart && task.failure?.startsWith("Original") == true && task.run?.outcome == TurnOutcome.Completed) {
            git.mergePreflight(current)
            task = task.preflightRecovered()
        }
        if (isPresent) task = builds.recoverBuilds(current, task)
        val restored = task
        val changed = update(chatId) {
            if (it.task.revision == current.task.revision) it.copy(task = restored) else it
        }
        return if (!restart && changed != null) actions.verify(changed) else changed
    }

    private suspend fun requireEnabled() {
        check(git.isAvailable && toggles.get(WorktreeModeEnabled)) { "WorktreeModeUnavailable" }
    }

    private fun requireAvailable() {
        check(git.isAvailable) { "WorktreeModeUnavailable" }
    }

    /** The chat's isolation choice; a sandbox is granted only while sandboxes are enabled. */
    private suspend fun isSandbox(command: WorktreeIntent.Public.Prepare): Boolean =
        command.isSandbox != false && toggles.get(WorktreeSandboxEnabled)

    internal suspend fun record(chatId: String): WorktreeRecord? = mutex.withLock { read()[chatId] }

    private suspend fun update(chatId: String, transform: (WorktreeRecord) -> WorktreeRecord): WorktreeRecord? =
        mutex.withLock {
            val saved = read()
            val previous = saved[chatId] ?: return@withLock null
            val changed = transform(previous)
            if (changed == previous) return@withLock previous
            val next = changed.copy(task = changed.task.copy(revision = previous.task.revision + 1))
            write(saved + (chatId to next))
            next
        }

    private suspend fun read(): Map<String, WorktreeRecord> {
        records?.let { return it }
        val raw = store.get(JournalKey)
        val decoded = try {
            if (raw == null) emptyList() else Json.decodeFromString<List<WorktreeRecord>>(raw)
        } catch (error: SerializationException) {
            // Serialization messages may contain filesystem data; preserve the corrupt bytes without logging them.
            log.e(IllegalStateException("InvalidWorktreeJournal (${error::class.simpleName.orEmpty()})")) {
                "Worktree journal could not be decoded"
            }
            error("InvalidWorktreeJournal (${error::class.simpleName.orEmpty()})")
        }
        check(decoded.map { it.task.chatId }.distinct().size == decoded.size) { "InvalidWorktreeJournal" }
        return decoded.associateBy { it.task.chatId }.also { records = it }
    }

    private suspend fun write(next: Map<String, WorktreeRecord>) {
        log.v { "Persist worktree journal count=${next.size}" }
        store.set(JournalKey, Json.encodeToString(next.values.toList()))
        records = next
        virtual.changed()
    }
}

/** A cancelled profile projection must not cancel the application-owned worker monitor. */
private suspend fun project(context: CoroutineContext, block: suspend () -> Unit) {
    CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
        coroutineContext.ensureActive()
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.tag(
                "WorktreeJournal",
            ).w(IllegalStateException("BuildProjectionFailed (${error::class.simpleName.orEmpty()})")) {
                "Worker remains active after projection failure"
            }
        }
    }.join()
}

private fun WorktreeIntent.Public.chatId(): String? = when (this) {
    is WorktreeIntent.Public.Prepare -> chatId

    is WorktreeIntent.Public.RunStarted -> chatId

    is WorktreeIntent.Public.RunAccepted -> chatId

    is WorktreeIntent.Public.RunRejected -> chatId

    is WorktreeIntent.Public.RunSubmissionObservationLost -> chatId

    is WorktreeIntent.Public.RunSubmissionReconciled -> chatId

    is WorktreeIntent.Public.RunObservationLost -> chatId

    is WorktreeIntent.Public.RunAdopted -> chatId

    is WorktreeIntent.Public.RunSettled -> chatId

    is WorktreeIntent.Public.TaskCompleteSignaled -> chatId

    is WorktreeIntent.Public.Recheck -> chatId

    WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
    is WorktreeIntent.Public.TrackMainSession, is WorktreeIntent.Public.ChooseAction,
    is WorktreeIntent.Public.ActionDelivered, is WorktreeIntent.Public.ActionDeliveryFailed,
    is WorktreeIntent.Public.ProposeBuildPlan, is WorktreeIntent.Public.ApproveBuildPlan,
    is WorktreeIntent.Public.RunBuild, is WorktreeIntent.Public.CancelBuild, is WorktreeIntent.Public.Seal,
    -> actionOrBuildChatId()
}

private fun WorktreeIntent.Public.actionOrBuildChatId(): String? = when (this) {
    is WorktreeIntent.Public.ChooseAction -> chatId

    is WorktreeIntent.Public.ActionDelivered -> chatId

    is WorktreeIntent.Public.ActionDeliveryFailed -> chatId

    is WorktreeIntent.Public.ProposeBuildPlan -> chatId

    is WorktreeIntent.Public.ApproveBuildPlan -> chatId

    is WorktreeIntent.Public.RunBuild -> chatId

    is WorktreeIntent.Public.CancelBuild -> chatId

    is WorktreeIntent.Public.Seal -> chatId

    WorktreeIntent.Public.Start, WorktreeIntent.Public.RetryLoad,
    is WorktreeIntent.Public.TrackMainSession, is WorktreeIntent.Public.Prepare,
    is WorktreeIntent.Public.RunStarted, is WorktreeIntent.Public.RunAccepted,
    is WorktreeIntent.Public.RunRejected, is WorktreeIntent.Public.RunSubmissionObservationLost,
    is WorktreeIntent.Public.RunSubmissionReconciled, is WorktreeIntent.Public.RunObservationLost,
    is WorktreeIntent.Public.RunAdopted, is WorktreeIntent.Public.RunSettled,
    is WorktreeIntent.Public.TaskCompleteSignaled, is WorktreeIntent.Public.Recheck,
    -> null
}

/** Asks the worker coordinator for the outcome of every build the saved task still counts as unfinished. */
private suspend fun WorktreeBuildCoordinator.recoverBuilds(
    record: WorktreeRecord,
    previous: WorktreeTask,
): WorktreeTask {
    var task = previous
    record.jobs.values.forEach { execution ->
        val known = task.builds[execution.id]
        if (known != null && known.phase !in terminalBuildPhases) {
            task = task.buildUpdated(recover(execution))
        }
    }
    return task
}

private const val MAX_RESULT_PROOFS = 64
