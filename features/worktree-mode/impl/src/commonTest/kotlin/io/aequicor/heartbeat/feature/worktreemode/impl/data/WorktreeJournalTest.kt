package io.aequicor.heartbeat.feature.worktreemode.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunIdentity
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorktreeJournalTest {
    @Test
    fun `failure before Git planning is durable and retry recreates the checkout`() = runTest {
        val fixture = Fixture()
        val prepare = WorktreeIntent.Public.Prepare(CHAT, PROJECT)
        fixture.git.hasPlanFailure = true
        assertFailsWith<IllegalStateException> { fixture.send(prepare) }
        fixture.journal.failed(prepare, "GitProcessUnavailable")
        assertEquals("GitProcessUnavailable", fixture.reopen().load()[CHAT]?.failure)
        fixture.git.hasPlanFailure = false
        fixture.send(prepare)
        assertEquals(WorktreePhase.Idle, fixture.task().phase)
        assertEquals(CHECKOUT, fixture.task().executionWorkspace)
    }

    @Test
    fun `completion choice is claimed once and survives an ambiguous action handoff`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val action = WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.CreatePr)
        val first = async { fixture.send(action) }
        val second = async { fixture.send(action) }
        first.await()
        second.await()
        assertEquals(1, fixture.git.promptCount)
        val operation = checkNotNull(fixture.task().actionRequest).operation
        fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, operation))
        val restored = checkNotNull(fixture.reopen().load()[CHAT])
        assertEquals(WorktreePhase.RecoveryRequired, restored.phase)
        assertEquals(operation, restored.expectedAction?.operation)
        assertEquals(1, fixture.git.promptCount)
    }

    @Test
    fun `a coding request winning between choice read and atomic claim cannot launch an action`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.stores.store.writeStarted = entered
        fixture.stores.store.writeGate = proceed
        val metadata = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, WorktreeBuildPlan("test", emptyList())))
        }
        entered.await()
        // The first write queues choice-lock lookup; the second queues its snapshot read before RunStarted.
        val choice = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge))
        }
        val secondEntered = CompletableDeferred<Unit>()
        val secondProceed = CompletableDeferred<Unit>()
        fixture.stores.store.writeStarted = secondEntered
        fixture.stores.store.writeGate = secondProceed
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.ApproveBuildPlan(CHAT, fixture.task().revision + 1, false))
        }
        proceed.complete(Unit)
        secondEntered.await()
        // FIFO ownership now forces old choice read, new run commit, then the losing action claim.
        val next = RequestId("next")
        val coding = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
        }
        secondProceed.complete(Unit)
        metadata.await()
        approval.await()
        choice.await()
        coding.await()
        val task = checkNotNull(fixture.journal.taskProjection(CHAT))
        assertEquals(next, task.run?.request)
        assertEquals(WorktreePhase.Working, task.phase)
        assertNull(task.actionRequest)
        assertNull(task.expectedAction)
        assertEquals(0, fixture.git.preflightCount)
        assertEquals(0, fixture.git.promptCount)
        assertEquals(0, fixture.builds.acquireCount)
    }

    @Test
    fun `late action prompt or failure cannot overwrite a recovered replacement coding run`() = runTest {
        for (hasFailure in listOf(false, true)) {
            val fixture = Fixture()
            fixture.completeCoding()
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            fixture.git.promptStarted = entered
            fixture.git.promptGate = proceed
            fixture.git.hasPromptFailure = hasFailure
            val choice = async {
                fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.CreatePr))
            }
            entered.await()
            fixture.journal.failed(WorktreeIntent.Public.Recheck(CHAT), "OriginalCheckoutDirty")
            fixture.send(WorktreeIntent.Public.Recheck(CHAT))
            val next = RequestId("next")
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
            proceed.complete(Unit)
            choice.await()
            val task = checkNotNull(fixture.journal.taskProjection(CHAT))
            assertEquals(next, task.run?.request)
            assertEquals(WorktreePhase.Working, task.phase)
            assertNull(task.actionRequest)
            assertNull(task.expectedAction)
            assertNull(task.failure)
            assertEquals(1, fixture.git.promptCount)
        }
    }

    @Test
    fun `recovery superseding a waiting merge claim releases its lease without delivering a prompt`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.builds.leaseStarted = entered
        fixture.builds.leaseGate = proceed
        val choice = async { fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge)) }
        entered.await()
        fixture.journal.failed(WorktreeIntent.Public.Recheck(CHAT), "OriginalCheckoutDirty")
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        val next = RequestId("next")
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
        proceed.complete(Unit)
        choice.await()
        val task = checkNotNull(fixture.journal.taskProjection(CHAT))
        assertEquals(next, task.run?.request)
        assertEquals(WorktreePhase.Working, task.phase)
        assertNull(task.actionRequest)
        assertNull(task.expectedAction)
        assertEquals(0, fixture.git.promptCount)
        assertEquals(1, fixture.builds.acquireCount)
        assertEquals(1, fixture.builds.releaseCount)
    }

    @Test
    fun `existing checkout continues after mode disabled and stale turn cannot release newer merge lease`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.toggles.isEnabled = false
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge))
        val action = checkNotNull(fixture.task().actionRequest)
        fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, action.operation))
        val mergeRequest = RequestId(action.operation)
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, mergeRequest, WorktreeRunKind.Merge))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, mergeRequest, SESSION, MERGE_TURN))
        val nested = WorktreeIntent.Public.RunBuild(CHAT, "nested", "test")
        val nestedFailure = assertFailsWith<IllegalStateException> { fixture.send(nested) }
        assertEquals("MergeInProgress", nestedFailure.message)
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
        assertEquals(0, fixture.builds.releaseCount)
        fixture.send(
            WorktreeIntent.Public.RunObservationLost(CHAT, mergeRequest, SESSION, MERGE_TURN, "ObservationLost"),
        )
        assertEquals(0, fixture.builds.releaseCount)
        assertNull(checkNotNull(fixture.task().run).outcome)
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, mergeRequest, SESSION, MERGE_TURN, TurnOutcome.Cancelled))
        assertEquals(1, fixture.builds.releaseCount)
    }

    @Test
    fun `only verified PR metadata is exposed and a new coding turn clears it`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.CreatePr))
        val action = checkNotNull(fixture.task().actionRequest)
        val request = RequestId(action.operation)
        fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, action.operation))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, request, WorktreeRunKind.CreatePr))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, request, SESSION, MERGE_TURN))
        fixture.send(
            WorktreeIntent.Public.TaskCompleteSignaled(
                CHAT,
                SESSION,
                MERGE_TURN,
                "PR created",
                "https://github.com/test/repo/pull/1",
            ),
        )
        assertNull(fixture.task().verifiedPullRequestUrl)
        fixture.git.isActionVerified = false
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, request, SESSION, MERGE_TURN, TurnOutcome.Completed))
        assertEquals(WorktreePhase.RecoveryRequired, fixture.task().phase)
        assertNull(fixture.task().verifiedPullRequestUrl)
        fixture.git.isActionVerified = true
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        assertEquals(WorktreePhase.Retained, fixture.task().phase)
        assertEquals(1, fixture.git.promptCount)
        assertEquals("https://github.com/test/repo/pull/1", fixture.task().verifiedPullRequestUrl)
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("next")))
        assertNull(fixture.task().verifiedPullRequestUrl)
    }

    @Test
    fun `rechecking an older snapshot cannot erase a concurrently accepted coding request`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.existsStarted = started
        fixture.git.existsGate = proceed
        val recheck = async { fixture.send(WorktreeIntent.Public.Recheck(CHAT)) }
        started.await()
        val next = RequestId("next")
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, next, SESSION, MERGE_TURN))
        proceed.complete(Unit)
        recheck.await()
        assertEquals(next, fixture.task().run?.request)
        assertEquals(MERGE_TURN, fixture.task().run?.turn)
        assertEquals(WorktreePhase.Working, fixture.task().phase)
    }

    @Test
    fun `explicit worker reconciliation resolves unknown status without replaying the command`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        fixture.send(
            WorktreeIntent.Public.ProposeBuildPlan(
                CHAT,
                WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))),
            ),
        )
        fixture.builds.phase = WorktreeBuildPhase.Unknown
        fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
        assertEquals(WorktreePhase.RecoveryRequired, fixture.task().phase)
        fixture.builds.phase = WorktreeBuildPhase.Completed
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        assertEquals(WorktreeBuildPhase.Completed, fixture.task().builds["build"]?.phase)
        assertEquals(1, fixture.builds.executeCount)
        assertNull(fixture.task().failure)
    }

    @Test
    fun `changing configuration during command preparation invalidates the approved revision atomically`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build")))
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan))
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.buildStarted = started
        fixture.git.buildGate = proceed
        val pending = async {
            assertFailsWith<IllegalStateException> {
                fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
            }
        }
        started.await()
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan))
        proceed.complete(Unit)
        assertEquals("BuildConfigurationChanged", pending.await().message)
        assertEquals(0, fixture.builds.executeCount)
        assertNull(fixture.task().builds["build"])
    }

    @Test
    fun `an old hosted turn cannot enqueue or configure after a replacement turn is accepted`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val old = WorktreeRunIdentity(RequestId("old"), SESSION, TURN)
        fixture.accept(old)
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build")))
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan, old))
        fixture.settle(old)
        fixture.accept(BUILD_IDENTITY)
        val late = WorktreeIntent.Public.RunBuild(CHAT, "late", "test", 1, old)
        assertEquals("TurnUnavailable", assertFailsWith<IllegalStateException> { fixture.send(late) }.message)
        fixture.journal.failed(late, "TurnUnavailable")
        assertEquals(emptyMap(), fixture.journal.taskProjection(CHAT)?.builds)
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan, old))
        assertEquals(1, fixture.task().buildConfigurationRevision)
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.buildStarted = started
        fixture.git.buildGate = proceed
        val preparing = async {
            assertFailsWith<IllegalStateException> {
                fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "preparing", "test", 1, BUILD_IDENTITY))
            }
        }
        started.await()
        fixture.settle(BUILD_IDENTITY)
        fixture.accept(WorktreeRunIdentity(RequestId("latest"), SESSION, TurnId("latest")))
        proceed.complete(Unit)
        assertEquals("TurnUnavailable", preparing.await().message)
        assertEquals(0, fixture.builds.executeCount)
        assertEquals(emptyMap(), fixture.task().builds)
    }

    @Test
    fun `trusted early hosted context binds pending native acceptance before Studio acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        val context = AgentToolContext(SESSION, CHECKOUT, TURN, request = REQUEST)
        assertNull(fixture.journal.authenticated(context.copy(request = RequestId("other"))))
        val bound = checkNotNull(fixture.journal.authenticated(context))
        assertEquals(SESSION, bound.run?.session)
        assertEquals(TURN, bound.run?.turn)
    }

    private class Fixture {
        val stores = MemoryStores()
        val git = FakeGit()
        val builds = FakeBuilds()
        val toggles = FakeToggles()
        val workspaces = FakeWorkspaces()
        val journal = reopen()
        private var current: WorktreeTask? = null
        fun reopen(): WorktreeJournal = WorktreeJournal(stores, git, builds, workspaces, toggles)
        suspend fun send(command: WorktreeIntent.Public) {
            journal.apply(command) { current = it }
        }
        fun task(): WorktreeTask = checkNotNull(current)
        suspend fun accept(identity: WorktreeRunIdentity) {
            send(WorktreeIntent.Public.RunStarted(CHAT, identity.request))
            send(WorktreeIntent.Public.RunAccepted(CHAT, identity.request, identity.session, identity.turn))
        }
        suspend fun settle(identity: WorktreeRunIdentity) {
            send(
                WorktreeIntent.Public.RunSettled(
                    CHAT,
                    identity.request,
                    identity.session,
                    identity.turn,
                    TurnOutcome.Completed,
                ),
            )
        }
        suspend fun completeCoding() {
            journal.load()
            send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
            send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
            send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
            send(WorktreeIntent.Public.TaskCompleteSignaled(CHAT, SESSION, TURN, "Done"))
            send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
            assertEquals(WorktreePhase.AwaitingDecision, task().phase)
        }
    }

    private companion object {
        const val CHAT = "chat"
        val PROJECT = WorkspaceRef("project")
        val CHECKOUT = WorkspaceRef("checkout")
        val REQUEST = RequestId("request")
        val TURN = TurnId("turn")
        val MERGE_TURN = TurnId("merge-turn")
        val SESSION = SessionRef(EngineId("test"), SessionSourceId("test"), "native")
        val BUILD_IDENTITY = WorktreeRunIdentity(RequestId("build-turn"), SESSION, MERGE_TURN)
    }

    private class FakeGit : WorktreeGit {
        override val isAvailable = true
        var hasPlanFailure = false
        var promptCount = 0
        var preflightCount = 0
        var hasPromptFailure = false
        var promptStarted: CompletableDeferred<Unit>? = null
        var promptGate: CompletableDeferred<Unit>? = null
        var isActionVerified = true
        var existsStarted: CompletableDeferred<Unit>? = null
        var existsGate: CompletableDeferred<Unit>? = null
        var buildStarted: CompletableDeferred<Unit>? = null
        var buildGate: CompletableDeferred<Unit>? = null
        override suspend fun plan(source: String, identity: String): WorktreeProvision {
            check(!hasPlanFailure) { "GitProcessUnavailable" }
            return WorktreeProvision("/source", "/checkout", "/common", "master", "heartbeat/task", "sha")
        }
        override suspend fun trackMain(source: String): WorktreeProvision = plan(source, "main")
        override suspend fun materialize(record: WorktreeRecord) = Unit
        override suspend fun exists(record: WorktreeRecord): Boolean {
            val gate = existsGate
            existsGate = null
            if (gate != null) {
                existsStarted?.complete(Unit)
                gate.await()
            }
            return true
        }
        override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) = Unit
        override suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution {
            buildStarted?.complete(Unit)
            buildGate?.await()
            return BuildExecution(
                id,
                WorktreeBuildCommand(command, "build"),
                record.directory,
                listOf(record.commonDirectory),
                "/jobs/$id",
            )
        }
        override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String {
            promptCount++
            promptStarted?.complete(Unit)
            promptGate?.await()
            check(!hasPromptFailure) { "ActionPromptFailed" }
            return "action"
        }
        override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = build(
            record,
            id,
            "lease",
        ).copy(isHold = true)
        override suspend fun mergePreflight(record: WorktreeRecord): String {
            preflightCount++
            return "sha"
        }
        override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?) =
            isActionVerified
    }

    private class FakeBuilds : WorktreeBuildCoordinator {
        var releaseCount = 0
        var acquireCount = 0
        var leaseStarted: CompletableDeferred<Unit>? = null
        var leaseGate: CompletableDeferred<Unit>? = null
        var phase = WorktreeBuildPhase.Completed
        var executeCount = 0
        override suspend fun execute(
            execution: BuildExecution,
            isReattachment: Boolean,
            update: suspend (WorktreeBuildOperation) -> Unit,
        ): WorktreeBuildOperation {
            executeCount++
            return WorktreeBuildOperation(execution.id, execution.command.id, phase)
        }
        override suspend fun cancel(id: String) = true
        override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation = WorktreeBuildOperation(
            execution.id,
            execution.command.id,
            phase,
        )
        override suspend fun acquireLease(execution: BuildExecution) {
            acquireCount++
            leaseStarted?.complete(Unit)
            leaseGate?.await()
        }
        override suspend fun releaseLease(execution: BuildExecution): Boolean {
            releaseCount++
            return true
        }
    }

    private class FakeToggles : FeatureToggles {
        var isEnabled = true

        // This fake only hosts the feature's Boolean flag.
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled as T
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = error("Unused")
    }

    private class FakeWorkspaces : LocalWorkspaces {
        override val isAvailable = true
        override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
        override suspend fun register(directory: String): LocalWorkspace = error("Unused")
        override suspend fun registerManaged(directory: String): LocalWorkspace = LocalWorkspace(CHECKOUT, "checkout")
        override suspend fun resolve(ref: WorkspaceRef): String = "/source"
    }
}

private class MemoryStores : DataStores {
    override val owner: StorageOwner = StorageOwner.App
    val store = MemoryStore(WorktreeJournalSpec)
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = store
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Unused")
    override suspend fun fire(event: DataEvent) = Unit
}

private class MemoryStore(override val spec: KeyValueSpec) : KeyValueStore {
    private val values = MutableStateFlow(emptyMap<String, Any>())
    var writeStarted: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null

    // StoreKey defines the value type in this in-memory test fake.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it[key.name] as T? }

    // StoreKey defines the value type in this in-memory test fake.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = values.value[key.name] as T?
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        val gate = writeGate
        writeGate = null
        if (gate != null) {
            writeStarted?.complete(Unit)
            gate.await()
        }
        values.value +=
            key.name to value
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }
}
