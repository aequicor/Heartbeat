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
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCache
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorktreeAgentToolsTest {
    @Test
    fun `configuration is an edit and cancellation is a command while status remains read only`() = runTest {
        val actions = Fixture().tools.specifications(PROJECT).associate { it.name to it.action }
        assertEquals(AgentToolAction.Edit, actions["configure_build"])
        assertEquals(AgentToolAction.Command, actions["cancel_build"])
        assertEquals(AgentToolAction.Command, actions["run_build"])
        assertEquals(AgentToolAction.Read, actions["build_status"])
        assertEquals(AgentToolAction.Read, actions["worktree_status"])
    }

    @Test
    fun `configuration approval shows complete plans and defaults beyond four thousand chars`() = runTest {
        val fixture = Fixture()
        val command = WorktreeBuildCommand(
            "test",
            "./gradlew",
            listOf("--no-daemon", "a".repeat(5_000) + "VISIBLE_TAIL"),
            environment = mapOf("GRADLE_USER_HOME" to "{cache:gradle}"),
        )
        val plan = WorktreeBuildPlan(
            "Gradle",
            listOf(command),
            listOf(WorktreeBuildCache("gradle", "shared-gradle")),
            listOf("device-one"),
        )
        val approval = fixture.approval("configure_build", Json.encodeToJsonElement(plan).jsonObject)
        val description = checkNotNull(approval.description)
        assertTrue(description.length > 4_000)
        assertTrue("VISIBLE_TAIL" in description)
        assertEquals(plan, Json.decodeFromString<WorktreeBuildPlan>(description.substringAfter('\n')))
        assertTrue("\"directory\":\".\"" in description)
        assertTrue("\"timeoutMillis\":900000" in description)
    }

    @Test
    fun `invisible plan text is escaped without changing the complete declaration`() = runTest {
        val fixture = Fixture()
        val command = WorktreeBuildCommand("test", "build", listOf("a\u202eb\n\t\u0007"))
        val plan = WorktreeBuildPlan("test", listOf(command))
        val approval = fixture.approval("configure_build", Json.encodeToJsonElement(plan).jsonObject)
        val description = checkNotNull(approval.description)
        assertTrue("\\u202e" in description)
        assertTrue('\u202e' !in description && '\u0007' !in description && '\t' !in description)
        assertEquals(plan, Json.decodeFromString<WorktreeBuildPlan>(description.substringAfter('\n')))
    }

    @Test
    fun `cancellation approval identifies the owned operation and entire resolved command`() = runTest {
        val fixture = Fixture()
        val command = WorktreeBuildCommand("test", "build", listOf("a".repeat(5_000) + "CANCEL_TAIL"))
        fixture.queue(WorktreeBuildPlan("test", listOf(command)))
        val approval = fixture.approval("cancel_build", operationArguments())
        val description = checkNotNull(approval.description)
        assertTrue("Operation: \"operation\"" in description)
        assertTrue("Configuration revision=1" in description)
        assertTrue("Working directory: \"/checkout\"" in description)
        assertTrue("CANCEL_TAIL" in description)
        assertTrue("\"timeoutMillis\":900000" in description)
        assertEquals("operation:1", approval.binding)
    }

    @Test
    fun `another operation or native turn cannot produce a cancellation approval`() = runTest {
        val fixture = Fixture()
        fixture.queue(WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))))
        assertEquals(
            "UnknownBuildOperation",
            assertFailsWith<IllegalStateException> {
                fixture.approval("cancel_build", operationArguments("foreign"))
            }.message,
        )
        assertEquals(
            "TurnUnavailable",
            assertFailsWith<IllegalStateException> {
                fixture.approval("cancel_build", operationArguments(), CONTEXT.copy(turn = TurnId("other")))
            }.message,
        )
    }

    @Test
    fun `oversized configuration and cancellation fail before producing any partial approval`() = runTest {
        val fixture = Fixture()
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build", listOf("a".repeat(20_000)))))
        val configuration = Json.encodeToJsonElement(plan).jsonObject
        assertEquals(
            "BuildApprovalTooLarge",
            assertFailsWith<IllegalStateException> { fixture.approval("configure_build", configuration) }.message,
        )
        fixture.queue(plan)
        assertEquals(
            "BuildApprovalTooLarge",
            assertFailsWith<IllegalStateException> { fixture.approval("cancel_build", operationArguments()) }.message,
        )
    }

    private class Fixture {
        private val workspaces = PreviewWorkspaces()
        private val toggles = PreviewToggles()
        private val journal = WorktreeJournal(PreviewStores(), PreviewGit(), PreviewBuilds(), workspaces, toggles)
        val tools = WorktreeAgentTools(journal, PreviewRegistry(), toggles, workspaces)

        suspend fun approval(
            name: String,
            arguments: JsonObject,
            context: AgentToolContext = CONTEXT,
        ): AgentToolApproval {
            val spec = tools.specifications(PROJECT).single { it.name == name }
            return tools.approval(context, spec, arguments)
        }

        suspend fun queue(plan: WorktreeBuildPlan) {
            journal.load()
            send(WorktreeIntent.Public.Prepare("chat", PROJECT))
            send(WorktreeIntent.Public.RunStarted("chat", REQUEST))
            send(WorktreeIntent.Public.RunAccepted("chat", REQUEST, SESSION, TURN))
            send(WorktreeIntent.Public.ProposeBuildPlan("chat", plan, IDENTITY))
            send(WorktreeIntent.Public.RunBuild("chat", "operation", plan.commands.single().id, 1, IDENTITY))
        }

        private suspend fun send(intent: WorktreeIntent.Public) = journal.apply(intent) {}
    }

    private companion object {
        val PROJECT = WorkspaceRef("project")
        val CHECKOUT = WorkspaceRef("checkout")
        val REQUEST = RequestId("request")
        val TURN = TurnId("turn")
        val SESSION = SessionRef(EngineId("test"), SessionSourceId("test"), "native")
        val CONTEXT = AgentToolContext(SESSION, CHECKOUT, TURN, REQUEST)
        val IDENTITY = WorktreeRunIdentity(REQUEST, SESSION, TURN)

        fun operationArguments(id: String = "operation") = JsonObject(mapOf("operation" to JsonPrimitive(id)))
    }
}

/** Approval preparation is read-only and must never invoke the machine. */
private class PreviewRegistry : MachineRegistry {
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(null)

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = error("Approval must not execute a machine intent")
}

private class PreviewGit : WorktreeGit {
    override val isAvailable = true
    override suspend fun plan(source: String, identity: String) =
        WorktreeProvision("/source", "/checkout", "/common", "master", "heartbeat/test", "sha")
    override suspend fun trackMain(source: String) = plan(source, "main")
    override suspend fun materialize(record: WorktreeRecord) = Unit
    override suspend fun exists(record: WorktreeRecord) = true
    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) = Unit
    override suspend fun build(record: WorktreeRecord, id: String, command: String) = BuildExecution(
        id,
        checkNotNull(record.task.buildPlan).commands.single { it.id == command },
        record.directory,
        listOf(record.commonDirectory),
        "/jobs/$id",
        configurationRevision = record.task.buildConfigurationRevision,
    )
    override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String = error("Unused")
    override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = error("Unused")
    override suspend fun mergePreflight(record: WorktreeRecord): String = error("Unused")
    override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?) = false
}

private class PreviewBuilds : WorktreeBuildCoordinator {
    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ) = WorktreeBuildOperation(
        execution.id,
        execution.command.id,
        WorktreeBuildPhase.Running,
        configurationRevision = execution.configurationRevision,
    )
    override suspend fun cancel(id: String) = false
    override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation = error("Unused")
    override suspend fun acquireLease(execution: BuildExecution) = Unit
    override suspend fun releaseLease(execution: BuildExecution) = true
}

private class PreviewWorkspaces : LocalWorkspaces {
    override val isAvailable = true
    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
    override suspend fun register(directory: String): LocalWorkspace = error("Unused")
    override suspend fun registerManaged(directory: String) = LocalWorkspace(WorkspaceRef("checkout"), "Checkout")
    override suspend fun resolve(ref: WorkspaceRef) = "/source"
}

private class PreviewToggles : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(toggle.default)

    // The feature reads its Boolean flag; unrelated declarations keep their own value type.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> true as T
        is FeatureToggle.Choice -> toggle.default
    }
}

private class PreviewStores : DataStores {
    private val store = PreviewStore()
    override val owner = StorageOwner.Profile(ProfileId("test"))
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = store
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Unused")
    override suspend fun fire(event: DataEvent) = Unit
}

private class PreviewStore : KeyValueStore {
    override val spec = WorktreeJournalSpec
    private val value = MutableStateFlow<String?>(null)

    // This journal stores only one string; the declaration owns its type.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = value.map { it as T? }

    @Suppress("UNCHECKED_CAST") // The same single string declaration is read synchronously.
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = value.value as T?
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        this.value.value = value as String
    }
    override suspend fun remove(key: StoreKey<*>) {
        value.value = null
    }
    override suspend fun clear() {
        value.value = null
    }
}
