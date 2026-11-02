package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowScope
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessConfigCodec
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessDecoded
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
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertNotNull
import kotlin.time.Instant

/** Compiles the shipped sources and runs the workflow with no real agent or scheduler. */
class IssueBacklogTest {
    @Test
    fun `shipped backlog rejects invalid explicit targets before invoking services`() = runTest {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve(".heartbeat")) }
        val folder = root.resolve(".heartbeat/harnesses/issue_e2e_closer")
        val files = Files.list(folder).use { entries ->
            entries.map {
                ConfigFile(ConfigPath.of("harnesses", "issue_e2e_closer", it.fileName.toString()), Files.readString(it))
            }.toList()
        }
        val unit = ConfigUnit(ConfigUnitRef(ConfigSectionId.Harnesses, "issue_e2e_closer"), files)
        val decoded = assertIs<HarnessDecoded.Valid>(HarnessConfigCodec.decode(unit))
        assertEquals(5, decoded.content.items.size)
        val directory = Files.createTempDirectory("issue-backlog-test")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val host = JvmHarnessScriptHost(directory, "test", backgroundScope, dispatchers)
        try {
            val sources = decoded.content.items.mapNotNull {
                when (it) {
                    is HarnessItem.Script -> Triple(it.name, HarnessCodeKind.Script, it.source)
                    is HarnessItem.Workflow -> Triple(it.name, HarnessCodeKind.Workflow, it.source)
                    else -> null
                }
            }
            assertEquals(2, sources.size)
            for ((name, kind, source) in sources) {
                val request = HarnessCompilationRequest(HarnessId("backlog"), ItemId(name.value), kind, source)
                val result = host.compile(request)
                val success = assertIs<HarnessCompilationResult.Success>(result, result.toString())
                try {
                    assertEquals(emptyList(), success.warnings, "Compiler warnings in ${name.value}")
                    if (kind == HarnessCodeKind.Workflow) {
                        var definition: WorkflowDefinition? = null
                        val context = HarnessEvaluationContext.Workflow(WorkflowRegistration { definition = it })
                        assertIs<HarnessEvaluationResult.Success>(host.evaluate(success.code, context))
                        verifyBacklogTargets(assertNotNull(definition))
                    }
                } finally {
                    success.code.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private suspend fun verifyBacklogTargets(definition: WorkflowDefinition) {
    val invalid = listOf(
        JsonPrimitive("#639"),
        JsonPrimitive("https://github.com/aequicor/Heartbeat/issues/639"),
        JsonPrimitive("abc"),
        JsonPrimitive(""),
        JsonPrimitive("  "),
        JsonPrimitive("0"),
        JsonPrimitive("-1"),
        JsonPrimitive("1.5"),
        JsonPrimitive(639),
        JsonNull,
    )
    for (value in invalid) {
        val scope = BacklogScope(JsonObject(mapOf("issue_number" to value)))
        assertFailsWith<IllegalArgumentException>("Input: $value") { definition(scope, scope.input) }
        assertEquals(emptyList(), scope.calls, "Rejected input must not read content or start an agent: $value")
    }
    val selected = BacklogScope(JsonObject(mapOf("issue_number" to JsonPrimitive(" 639 "))))
    val result = definition(selected, selected.input).jsonObject
    assertEquals(JsonPrimitive("issue #639"), result["target"])
    assertEquals(JsonPrimitive("report"), result["report"])
    assertEquals(listOf("skill:process_issue", "prompt:process_next", "agent:issue #639"), selected.calls)
    val automatic = BacklogScope(JsonObject(emptyMap()))
    val autoResult = definition(automatic, automatic.input).jsonObject
    val autoTarget = "автоматический выбор следующей подходящей открытой issue"
    assertEquals(JsonPrimitive(autoTarget), autoResult["target"])
    assertEquals(listOf("skill:process_issue", "prompt:process_next", "agent:$autoTarget"), automatic.calls)
}

private class BacklogScope(override val input: JsonObject) : WorkflowScope {
    val calls = mutableListOf<String>()
    private var target = ""

    override fun skill(name: ItemName): String {
        calls += "skill:${name.value}"
        return "skill"
    }

    override fun prompt(name: ItemName, args: Map<String, String>): String {
        calls += "prompt:${name.value}"
        target = args.getValue("run_mode")
        return target
    }

    override suspend fun agent(prompt: String, options: AgentOptions): String {
        assertEquals("skill\n\n$target", prompt)
        assertEquals("Heartbeat issue + e2e: $target", options.title)
        calls += "agent:$target"
        return "report"
    }

    override suspend fun organism(goal: String, options: OrganismOptions): OrganismOutcome = error("Unexpected service")

    override suspend fun parallel(vararg branches: suspend WorkflowScope.() -> JsonElement): List<JsonElement> =
        error("Unexpected service")

    override suspend fun step(name: String, body: suspend () -> JsonElement): JsonElement = error("Unexpected service")

    override suspend fun now(): Instant = error("Unexpected service")
}
