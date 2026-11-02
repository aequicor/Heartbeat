package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommands
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUi
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowScope
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessConfigCodec
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessDecoded
import io.aequicor.heartbeat.feature.harness.impl.domain.content.renderHarnessTemplate
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Compiles and executes the checked-in harness against the actual host and content codec. */
class IssueE2eCloserTest {
    @Test
    fun `workflow keeps explicit targets and rejects invalid targets before starting a helper`() = runTest {
        val items = issueCloserItems()
        val workflow = items.filterIsInstance<HarnessItem.Workflow>().single()
        withIssueCloserCode(workflow.name, HarnessCodeKind.Workflow, workflow.source) { host, compiled ->
            val definitions = mutableListOf<WorkflowDefinition>()
            val context = HarnessEvaluationContext.Workflow(WorkflowRegistration { definitions += it })
            assertIs<HarnessEvaluationResult.Success>(host.evaluate(compiled.code, context))
            val definition = definitions.single()
            listOf("641", " 641 ").forEach { number ->
                val input = JsonObject(mapOf("issue_number" to JsonPrimitive(number)))
                val scope = IssueCloserWorkflowScope(items, input)
                val result = definition(scope, input).jsonObject
                assertEquals(JsonPrimitive("issue #641"), result["target"])
                assertEquals(JsonPrimitive("helper report"), result["report"])
                assertTrue(scope.requests.single().first.contains("issue #641"))
                assertTrue(scope.requests.single().first.contains(scope.skill(ItemName("process_issue"))))
                assertTrue("{{run_mode}}" !in scope.requests.single().first)
            }
            val automatic = IssueCloserWorkflowScope(items, JsonObject(emptyMap()))
            definition(automatic, automatic.input)
            assertTrue(automatic.requests.single().first.contains("автоматический выбор"))
            listOf(
                JsonPrimitive(""),
                JsonPrimitive(" "),
                JsonPrimitive("#641"),
                JsonPrimitive("0"),
                JsonPrimitive("-1"),
                JsonPrimitive("6.41"),
                JsonPrimitive("9223372036854775808"),
                JsonPrimitive(641),
                JsonPrimitive(false),
                JsonNull,
                JsonObject(emptyMap()),
            ).forEach { value ->
                val input = JsonObject(mapOf("issue_number" to value))
                val scope = IssueCloserWorkflowScope(items, input)
                assertFailsWith<IllegalArgumentException>("Invalid value: $value") { definition(scope, input) }
                assertTrue(scope.requests.isEmpty(), "Invalid input must not select a different issue")
            }
        }
    }

    @Test
    fun `daily script registers a timer without starting work during evaluation`() = runTest {
        val script = issueCloserItems().filterIsInstance<HarnessItem.Script>().single()
        withIssueCloserCode(script.name, HarnessCodeKind.Script, script.source) { host, compiled ->
            val scope = IssueCloserScriptScope(backgroundScope)
            assertIs<HarnessEvaluationResult.Success>(
                host.evaluate(compiled.code, HarnessEvaluationContext.Script(scope)),
            )
            assertTrue(scope.starts.isEmpty())
            val timer = scope.timers.single()
            assertEquals(24.hours, timer.first)
            timer.second()
            assertEquals(listOf(ItemName("process_backlog") to JsonObject(emptyMap())), scope.starts)
            scope.startFailure = IllegalStateException("Unavailable workflow host")
            assertFailsWith<IllegalStateException> { timer.second() }
        }
    }
}

private fun issueCloserItems(): List<HarnessItem> {
    val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve(".heartbeat")) }
    val folder = root.resolve(".heartbeat/harnesses/issue_e2e_closer")
    val files = Files.list(folder).use { entries ->
        entries.map {
            ConfigFile(ConfigPath.of("harnesses", "issue_e2e_closer", it.fileName.toString()), Files.readString(it))
        }.toList()
    }
    val unit = ConfigUnit(ConfigUnitRef(ConfigSectionId.Harnesses, "issue_e2e_closer"), files)
    return assertIs<HarnessDecoded.Valid>(HarnessConfigCodec.decode(unit)).content.items
}

private suspend fun TestScope.withIssueCloserCode(
    name: ItemName,
    kind: HarnessCodeKind,
    source: String,
    verify: suspend (JvmHarnessScriptHost, HarnessCompilationResult.Success) -> Unit,
) {
    val directory = Files.createTempDirectory("issue-closer-test")
    val dispatcher = StandardTestDispatcher(testScheduler)
    val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    val host = JvmHarnessScriptHost(directory, "test", backgroundScope, dispatchers)
    try {
        val result = host.compile(
            HarnessCompilationRequest(HarnessId("issue-closer"), ItemId(name.value), kind, source),
        )
        val diagnostic = (result as? HarnessCompilationResult.Failure)?.diagnostics?.joinToString { it.message }
        val compiled = assertIs<HarnessCompilationResult.Success>(result, diagnostic)
        try {
            assertTrue(compiled.warnings.isEmpty(), compiled.warnings.joinToString { it.message })
            verify(host, compiled)
        } finally {
            compiled.code.close()
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private class IssueCloserWorkflowScope(private val items: List<HarnessItem>, override val input: JsonObject) :
    WorkflowScope {
    val requests = mutableListOf<Pair<String, AgentOptions>>()
    override suspend fun agent(prompt: String, options: AgentOptions): String {
        requests += prompt to options
        return "helper report"
    }
    override fun prompt(name: ItemName, args: Map<String, String>): String =
        renderHarnessTemplate(items.filterIsInstance<HarnessItem.Template>().single { it.name == name }, args)

    override fun skill(name: ItemName): String =
        items.filterIsInstance<HarnessItem.Skill>().single { it.name == name }.body

    override suspend fun organism(goal: String, options: OrganismOptions): OrganismOutcome = error(
        "Unexpected organism",
    )
    override suspend fun parallel(vararg branches: suspend WorkflowScope.() -> JsonElement): List<JsonElement> =
        error("Unexpected parallel work")
    override suspend fun step(name: String, body: suspend () -> JsonElement): JsonElement = error("Unexpected step")
    override suspend fun now(): Instant = error("Unexpected clock")
}

private class IssueCloserScriptScope(override val scope: CoroutineScope) : HarnessScriptScope {
    override val harness = HarnessId("issue-closer")
    override val name = HarnessName("issue_e2e_closer")
    override val item = ItemId("daily_backlog")
    override val revision = 1L
    val timers = mutableListOf<Pair<Duration, suspend () -> Unit>>()
    val starts = mutableListOf<Pair<ItemName, JsonObject>>()
    var startFailure: Exception? = null
    override val scheduler = object : ScriptScheduler {
        override fun every(interval: Duration, handler: suspend () -> Unit): ScriptRegistration {
            timers += interval to handler
            return ScriptRegistration {}
        }
        override fun at(instant: Instant, handler: suspend () -> Unit): ScriptRegistration = error(
            "Unexpected deadline",
        )
        override suspend fun wake(session: SessionRef, condition: WakeCondition, note: String): WakeId =
            error("Unexpected wake")
        override suspend fun cancel(id: WakeId): Boolean = error("Unexpected cancellation")
        override suspend fun publish(name: ItemName, payload: String?): EventKey = error("Unexpected event")
    }
    override val workflows = object : ScriptWorkflows {
        override suspend fun start(name: ItemName, input: JsonObject): RunId {
            startFailure?.let { throw it }
            starts += name to input
            return RunId("wf_issue_closer_test")
        }
    }
    override val events: ScriptEvents get() = error("Unexpected service")
    override val hooks: ScriptHooks get() = error("Unexpected service")
    override val sessions: ScriptSessions get() = error("Unexpected service")
    override val agent: ScriptAgent get() = error("Unexpected service")
    override val prompts: ScriptPrompts get() = error("Unexpected service")
    override val data: ScriptData get() = error("Unexpected service")
    override val processes: ScriptProcesses get() = error("Unexpected service")
    override val ui: ScriptUi get() = error("Unexpected service")
    override val commands: ScriptCommands get() = error("Unexpected service")
}
