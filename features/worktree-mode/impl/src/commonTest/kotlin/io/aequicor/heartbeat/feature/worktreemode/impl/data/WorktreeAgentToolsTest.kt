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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCache
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunIdentity
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `a build is still cancellable after the shared project configuration moved on`() = runTest {
        val fixture = Fixture()
        fixture.queue(WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build", listOf("OWN_BUILD")))))
        fixture.git.previewFailure = "ProjectBuildConfigurationChanged"
        val approval = fixture.approval("cancel_build", operationArguments())
        val description = checkNotNull(approval.description)
        assertTrue("Operation: \"operation\"" in description)
        assertTrue("Configuration revision=1" in description)
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
    fun `large configuration and cancellation retain the complete operation`() = runTest {
        val fixture = Fixture()
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build", listOf("a".repeat(20_000)))))
        val configuration = Json.encodeToJsonElement(plan).jsonObject
        assertTrue(
            fixture.approval("configure_build", configuration).description.orEmpty().contains("a".repeat(20_000)),
        )
        fixture.queue(plan)
        assertTrue(
            fixture.approval("cancel_build", operationArguments()).description.orEmpty().contains("a".repeat(20_000)),
        )
    }

    @Test
    fun `sandbox sessions are told to build only through the queue with declared kinds`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.queue(
            WorktreeBuildPlan(
                "Gradle",
                listOf(WorktreeBuildCommand("test", "./gradlew", kind = WorktreeBuildKind.Test)),
                listOf(WorktreeBuildCache("gradle", "shared-gradle", isConcurrent = true)),
            ),
        )
        val instructions = fixture.tools.instructions(CHECKOUT)
        assertTrue("sandbox that keeps only sources" in instructions, instructions)
        assertTrue("Declare each command's kind" in instructions, instructions)
        val schema = fixture.tools.specifications(CHECKOUT).single { it.name == "configure_build" }.inputSchema
        assertTrue("\"Native\"" in schema.toString() && "\"concurrent\"" in schema.toString())
        val arguments = Json.encodeToJsonElement(
            WorktreeBuildPlan(
                "Gradle",
                listOf(WorktreeBuildCommand("test", "./gradlew", kind = WorktreeBuildKind.Test)),
                listOf(WorktreeBuildCache("gradle", "shared-gradle", isConcurrent = true)),
            ),
        ).jsonObject
        val approval = fixture.approval("configure_build", arguments).toString()
        assertTrue("Test" in approval && "concurrent" in approval, approval)
        val build = fixture.approval("run_build", JsonObject(mapOf("command" to JsonPrimitive("test")))).toString()
        assertTrue("shared warm build slot" in build && "/common/slots" in build, build)
        assertTrue("Working directory" !in build, build)
    }

    @Test
    fun `a build requires the exact operation authorization`() = runTest {
        val fixture = Fixture()
        val configure = fixture.spec("configure_build")
        val plan = planArguments(WorktreeBuildCommand("test", "build"))
        val other = planArguments(WorktreeBuildCommand("test", "other"))
        assertEquals("AuthorizationChanged", fixture.tools.execute(AUTOMATIC, "configure_build", plan).text)
        val bound = fixture.tools.approval(AUTOMATIC, configure, plan)
        val authorized = AUTOMATIC.copy(authorization = bound)
        assertEquals("AuthorizationChanged", fixture.tools.execute(authorized, "configure_build", other).text)
        assertTrue(fixture.tools.execute(authorized, "configure_build", plan).text != "AuthorizationChanged")
    }

    @Test
    fun `the build keeps the configuration revision inside the operation binding`() = runTest {
        val fixture = Fixture()
        fixture.queue(WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))))
        val run = fixture.spec("run_build")
        val arguments = JsonObject(mapOf("command" to JsonPrimitive("test")))
        val interactive = fixture.tools.approval(CONTEXT, run, arguments)
        val automatic = fixture.tools.approval(AUTOMATIC, run, arguments)
        assertEquals("1", interactive.binding)
        assertEquals(interactive.binding, automatic.binding)
        assertEquals(interactive.description, automatic.description)
    }

    @Test
    fun `a build call returns the terminal result instead of a queued operation to poll`() = runTest {
        val fixture = Fixture(isLive = true)
        fixture.queue(WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))))
        val call = async { fixture.execute(CONTEXT, "run_build", BUILD_ARGUMENTS) }
        advanceTimeBy(BUILD_PROGRESS)
        assertFalse(call.isCompleted)
        fixture.registry.finishBuilds(WorktreeBuildPhase.Failed)
        val result = call.await()
        assertTrue(result.isError)
        assertTrue("\"Failed\"" in result.text && "Still" !in result.text, result.text)
    }

    @Test
    fun `a build still pending after the long wait is handed to build status which waits again`() = runTest {
        val fixture = Fixture(isLive = true)
        fixture.queue(WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))))
        val pending = fixture.execute(CONTEXT, "run_build", BUILD_ARGUMENTS)
        assertFalse(pending.isError)
        assertTrue("Still Running" in pending.text, pending.text)
        val operation = Json.decodeFromString<WorktreeBuildOperation>(pending.text.lineSequence().first()).id
        val status = async { fixture.execute(CONTEXT, "build_status", operationArguments(operation)) }
        advanceTimeBy(BUILD_PROGRESS)
        assertFalse(status.isCompleted)
        fixture.registry.finishBuilds(WorktreeBuildPhase.Completed)
        val result = status.await()
        assertFalse(result.isError)
        assertTrue("\"Completed\"" in result.text && "Still" !in result.text, result.text)
    }

    @Test
    fun `a rejected build plan is explained at once instead of timing out`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        fixture.git.rejection = "NeedsConfiguration"
        val plan = planArguments(WorktreeBuildCommand("test", "build"))
        val result = fixture.execute(CONTEXT, "configure_build", plan)
        assertTrue(result.isError)
        assertTrue(result.text.startsWith("NeedsConfiguration: configure at least one project command"), result.text)
        fixture.git.rejection = "path /Users/secret is not allowed"
        assertEquals("InvalidBuildPlan", fixture.execute(CONTEXT, "configure_build", plan).text)
    }

    @Test
    fun `a checkout failure during plan validation keeps its plain code for the agent`() = runTest {
        val fixture = Fixture()
        fixture.prepare()
        val plan = planArguments(WorktreeBuildCommand("test", "build"))
        fixture.git.failure = IllegalStateException("WorktreeUnavailable")
        assertEquals("WorktreeUnavailable", fixture.execute(CONTEXT, "configure_build", plan).text)
        fixture.git.failure = IllegalStateException("git failed in /Users/secret/repo")
        assertEquals("OperationFailed", fixture.execute(CONTEXT, "configure_build", plan).text)
    }

    private class Fixture(isLive: Boolean = false) {
        private val workspaces = PreviewWorkspaces()
        val toggles = PreviewToggles()
        val git = PreviewGit()
        private val journal = WorktreeJournal(PreviewStores(), git, PreviewBuilds(), workspaces, toggles)
        val registry = JournalRegistry(journal)
        val tools = WorktreeAgentTools(
            journal,
            if (isLive) registry else PreviewRegistry(),
            toggles,
            workspaces,
        )

        suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) = tools.execute(
            context.copy(authorization = tools.approval(context, spec(name), arguments)),
            name,
            arguments,
        )

        suspend fun spec(name: String) = tools.specifications(PROJECT).single { it.name == name }

        suspend fun approval(
            name: String,
            arguments: JsonObject,
            context: AgentToolContext = CONTEXT,
        ): AgentToolApproval {
            val spec = tools.specifications(PROJECT).single { it.name == name }
            return tools.approval(context, spec, arguments)
        }

        suspend fun prepare() {
            journal.load()
            send(WorktreeIntent.Public.Prepare("chat", PROJECT))
            send(WorktreeIntent.Public.RunStarted("chat", REQUEST))
            send(WorktreeIntent.Public.RunAccepted("chat", REQUEST, SESSION, TURN))
        }

        suspend fun queue(plan: WorktreeBuildPlan) {
            prepare()
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
        val AUTOMATIC = CONTEXT.copy(
            callId = ToolCallId("call"),
            target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model")),
            decisionMode = AgentToolDecisionMode.AutomaticSafety,
        )
        val IDENTITY = WorktreeRunIdentity(REQUEST, SESSION, TURN)
        val BUILD_ARGUMENTS = JsonObject(mapOf("command" to JsonPrimitive("test")))
        const val BUILD_PROGRESS = 60_000L

        fun operationArguments(id: String = "operation") = JsonObject(mapOf("operation" to JsonPrimitive(id)))

        fun planArguments(command: WorktreeBuildCommand): JsonObject =
            Json.encodeToJsonElement(WorktreeBuildPlan("test", listOf(command))).jsonObject
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

/** Applies public intents to the real journal and projects its tasks the way the machine state does. */
private class JournalRegistry(journal: WorktreeJournal) : MachineRegistry {
    private val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready())
    private val ref = object : MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
        override val name: String = WorktreeMachineKey.name
        override val state: StateFlow<WorktreeState> = this@JournalRegistry.state
        override val outputs: Flow<WorktreeOutput> = emptyFlow()
        override suspend fun send(intent: WorktreeIntent.Public): SendResult {
            journal.apply(intent) { task -> project(task.chatId) { task } }
            return SendResult.Accepted
        }
    }

    /** Settles every build of the projected tasks, as the coordinator does when the process exits. */
    fun finishBuilds(phase: WorktreeBuildPhase) = tasks().keys.forEach { chatId ->
        project(chatId) { task ->
            checkNotNull(task).copy(builds = task.builds.mapValues { it.value.copy(phase = phase) })
        }
    }

    private fun tasks(): Map<String, WorktreeTask> = (state.value as WorktreeState.Ready).tasks

    private fun project(chatId: String, transform: (WorktreeTask?) -> WorktreeTask) = state.update { current ->
        val tasks = (current as WorktreeState.Ready).tasks
        WorktreeState.Ready(tasks + (chatId to transform(tasks[chatId])))
    }

    @Suppress("UNCHECKED_CAST") // The fake serves only WorktreeMachineKey.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O> = ref as MachineRef<S, P, O>

    @Suppress("UNCHECKED_CAST") // The fake serves only WorktreeMachineKey.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(ref) as StateFlow<MachineRef<S, P, O>?>

    @Suppress("UNCHECKED_CAST") // The fake serves only WorktreeMachineKey.
    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = (ref as MachineRef<S, P, O>).send(intent)
}

private class PreviewGit : WorktreeGit {
    var rejection: String? = null
    var failure: Exception? = null
    override val isAvailable = true
    override suspend fun plan(source: String, identity: String) =
        WorktreeProvision("/source", "/checkout", "/common", "master", "heartbeat/test", "sha")
    override suspend fun trackMain(source: String) = plan(source, "main")
    override suspend fun materialize(record: WorktreeRecord) = Unit
    override suspend fun exists(record: WorktreeRecord) = true
    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) {
        failure?.let { throw it }
        rejection?.let { throw IllegalArgumentException(it) }
    }
    var previewFailure: String? = null
    override suspend fun build(record: WorktreeRecord, id: String, command: String) = previewFailure
        ?.takeIf { id == "approval" }?.let { error(it) } ?: BuildExecution(
        id,
        checkNotNull(record.task.buildPlan).commands.single { it.id == command },
        record.directory,
        listOf(record.commonDirectory),
        "/jobs/$id",
        configurationRevision = record.task.buildConfigurationRevision,
        sandbox = if (record.task.isSandbox) SandboxBuild("", "/common/slots", "/source") else null,
    )
    override suspend fun snapshot(record: WorktreeRecord, id: String): String = "snapshot-$id"
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
    var isSandbox = false

    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(toggle.default)

    // The feature reads its Boolean flag; unrelated declarations keep their own value type.
    @Suppress("UNCHECKED_CAST") // The fake serves only WorktreeMachineKey.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> (toggle != WorktreeSandboxEnabled || isSandbox) as T
        is FeatureToggle.Choice -> toggle.default
    }
}

private class PreviewStores : DataStores {
    private val store = PreviewStore()
    override val owner = StorageOwner.Profile(ProfileId("test"))
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = store
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Unused")
    override fun filesDirectory(name: String): String = error("Unused")
    override suspend fun fire(event: DataEvent) = Unit
}

private class PreviewStore : KeyValueStore {
    override val spec = WorktreeJournalSpec
    private val value = MutableStateFlow<String?>(null)

    // This journal stores only one string; the declaration owns its type.
    @Suppress("UNCHECKED_CAST") // The fake serves only WorktreeMachineKey.
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
