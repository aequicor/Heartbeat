package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SandboxSlotSweeperStartupTest {
    @Test
    fun `startup waits for restoration and completion releases slots without a setting change`() = runTest {
        val fixture = SlotSweepFixture(backgroundScope)
        fixture.seed()
        fixture.sweeper.start()
        runCurrent()
        assertFalse(fixture.journal.isInitialized())
        assertEquals(emptyList(), fixture.git.kept)

        fixture.ready()
        runCurrent()
        assertEquals(listOf(2), fixture.git.kept)
        fixture.journal.value.apply(WorktreeIntent.Public.ChooseAction("chat", WorktreeAction.Leave)) { task ->
            fixture.machine.state.value = WorktreeState.Ready(mapOf(task.chatId to task))
        }
        runCurrent()
        assertEquals(listOf(2, 0), fixture.git.kept)

        val task = checkNotNull(fixture.journal.value.taskProjection("chat"))
        fixture.machine.state.value = WorktreeState.Ready(mapOf(task.chatId to task.copy(revision = task.revision + 1)))
        runCurrent()
        assertEquals(listOf(2, 0), fixture.git.kept)
    }

    @Test
    fun `busy surplus slots are retried after their worker releases them`() = runTest {
        val fixture = SlotSweepFixture(backgroundScope)
        fixture.seed(WorktreePhase.Retained)
        fixture.git.isBusy = true
        fixture.sweeper.start()
        runCurrent()
        fixture.ready()
        runCurrent()
        assertEquals(listOf(0), fixture.git.kept)

        fixture.git.isBusy = false
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(0, 0), fixture.git.kept)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0, 0), fixture.git.kept)
    }

    @Test
    fun `a changed budget replaces the pending retry policy and profile closure stops retries`() = runTest {
        val profile = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
        )
        val fixture = SlotSweepFixture(profile)
        fixture.seed()
        fixture.git.isBusy = true
        fixture.sweeper.start()
        runCurrent()
        fixture.ready()
        runCurrent()
        assertEquals(listOf(2), fixture.git.kept)

        fixture.capacity.current.value = BuildBudget.parallel(1)
        runCurrent()
        assertEquals(listOf(2, 1), fixture.git.kept)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(2, 1, 1), fixture.git.kept)

        profile.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(2, 1, 1), fixture.git.kept)
    }
}

private class SlotSweepFixture(scope: CoroutineScope) {
    val git = SweepGit()
    val machine = SweepMachine()
    val capacity = SweepCapacity()
    private val stores = SettingsStores()
    private val toggles = SweepToggles()
    private val workspaces = SweepWorkspaces()
    private var task = WorktreeTask(
        "chat",
        WorkspaceRef("project"),
        phase = WorktreePhase.AwaitingDecision,
        isSandbox = true,
    )
    val journal = lazy { WorktreeJournal(stores, git, SweepBuilds(), workspaces, toggles) }
    val sweeper = SandboxSlotSweeper(
        journal,
        lazy { git },
        capacity,
        toggles,
        lazy { workspaces },
        lazy { machine },
        SweepScope(scope),
    )

    suspend fun seed(phase: WorktreePhase = WorktreePhase.AwaitingDecision) {
        task = task.copy(phase = phase)
        val record = WorktreeRecord(task, "/source", "/checkout", "/common", "provisioned")
        stores.keyValue(WorktreeJournalSpec).set(
            stringKey("records"),
            Json.encodeToString(listOf(record)),
        )
    }

    fun ready() {
        machine.state.value = WorktreeState.Ready(mapOf(task.chatId to task))
    }
}

private class SweepMachine : Machine<WorktreeState, WorktreeIntent, WorktreeOutput> {
    override val name = "worktree-mode"
    override val state = MutableStateFlow<WorktreeState>(WorktreeState.Loading)
    override val outputs: Flow<WorktreeOutput> = emptyFlow()
    override suspend fun send(intent: WorktreeIntent) = SendResult.Accepted
}

private class SweepCapacity : BuildCapacity {
    val current = MutableStateFlow(BuildBudget.parallel(2))
    override val automatic = current.value
    override suspend fun budget(): BuildBudget = current.value
    override fun changes(): Flow<Unit> = current.map { }
}

private class SweepToggles : FeatureToggles {
    // These startup fixtures observe only the worktree and sandbox Boolean flags.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf((toggle != WorktreeSandboxEnabled) as T)

    // The journal reads only the worktree Boolean flag for this completion action.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = (toggle != WorktreeSandboxEnabled) as T
}

private class SweepWorkspaces : LocalWorkspaces {
    override val isAvailable = true
    override fun observe(): Flow<List<LocalWorkspace>> =
        flowOf(listOf(LocalWorkspace(WorkspaceRef("project"), "Project")))
    override suspend fun resolve(ref: WorkspaceRef) = "/source"
    override suspend fun register(directory: String): LocalWorkspace = error("Unused")
}

private class SweepScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "slot-sweeper"
    override val savedState: ScopeSavedState get() = error("Unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

private class SweepGit : WorktreeGit {
    val kept = mutableListOf<Int>()
    var isBusy = false
    override val isAvailable = true
    override suspend fun sweepSlots(record: WorktreeRecord, keep: Int): Boolean {
        kept += keep
        return isBusy
    }
    override suspend fun plan(source: String, identity: String): WorktreeProvision = error("Unused")
    override suspend fun trackMain(source: String): WorktreeProvision = error("Unused")
    override suspend fun materialize(record: WorktreeRecord) = Unit
    override suspend fun exists(record: WorktreeRecord) = true
    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) = Unit
    override suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution = error("Unused")
    override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String = error("Unused")
    override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = error("Unused")
    override suspend fun mergePreflight(record: WorktreeRecord): String = error("Unused")
    override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?) = false
}

private class SweepBuilds : WorktreeBuildCoordinator {
    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation = error("Unused")
    override suspend fun cancel(id: String) = false
    override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation = error("Unused")
    override suspend fun acquireLease(execution: BuildExecution) = Unit
    override suspend fun releaseLease(execution: BuildExecution) = true
}
