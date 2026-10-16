package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommand
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommands
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUi
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Executes the reference's actual compiled callback with bounded and malformed successful command output. */
internal suspend fun verifyUiExampleCommandOutput(
    host: HarnessScriptHost,
    code: CompiledHarnessCode,
    scope: CoroutineScope,
) {
    val fixture = UiExampleScope(scope)
    assertIs<HarnessEvaluationResult.Success>(host.evaluate(code, HarnessEvaluationContext.Script(fixture)))
    val call = ScriptUiCall(buildJsonObject { put("repo", "owner/repository") }, workspace = WorkspaceRef("example"))
    val prs = JsonArray(
        List(50) { index ->
            buildJsonObject {
                put("number", index + 1)
                put("title", "Example pull request title ".repeat(4))
                put("url", "https://github.com/owner/repository/pull/${index + 1}")
                put("headRefName", "feature-$index")
                put("baseRefName", "main")
            }
        },
    )
    val output = prs.toString()
    assertTrue(output.length > 7_000)
    // The contained command runner keeps this head and tail, with an omission marker between them.
    val truncated = output.take(2_000) + "\n… ${output.length - 7_000} bytes omitted …\n" + output.takeLast(5_000)
    val invalidOutputs = mapOf("truncated" to truncated, "incomplete" to "[", "object" to "{}", "scalar array" to "[1]")
    for ((label, invalid) in invalidOutputs) {
        fixture.output = invalid
        val result = assertIs<ScriptUiResult.Error>(fixture.call(call), label)
        assertEquals("gh output was incomplete or invalid; request fewer pull requests", result.message)
    }
    fixture.output = JsonArray(prs.take(1)).toString()
    val recovered = assertIs<ScriptUiResult.Value>(fixture.call(call))
    assertEquals(JsonPrimitive(1), recovered.value.jsonObject["count"])
}

private class UiExampleScope(override val scope: CoroutineScope) : HarnessScriptScope {
    override val harness = HarnessId("reference")
    override val name = HarnessName("reference")
    override val item = ItemId("methods")
    override val revision = 1L
    var output = ""
    private var handler: (suspend (ScriptUiCall) -> ScriptUiResult)? = null
    override val commands = object : ScriptCommands {
        override fun define(name: ItemName, command: ScriptCommand): ScriptRegistration = ScriptRegistration {}
        override suspend fun run(
            name: ItemName,
            workspace: WorkspaceRef?,
            parameters: Map<String, String>,
        ): ScriptCommandResult = ScriptCommandResult.Exited(0, output)
    }
    override val ui = object : ScriptUi {
        override fun method(
            name: ItemName,
            description: String,
            handler: suspend (ScriptUiCall) -> ScriptUiResult,
        ): ScriptRegistration {
            check(this@UiExampleScope.handler == null)
            this@UiExampleScope.handler = handler
            return ScriptRegistration {}
        }
    }
    override val events: ScriptEvents get() = error("Unexpected service")
    override val hooks: ScriptHooks get() = error("Unexpected service")
    override val sessions: ScriptSessions get() = error("Unexpected service")
    override val scheduler: ScriptScheduler get() = error("Unexpected service")
    override val agent: ScriptAgent get() = error("Unexpected service")
    override val prompts: ScriptPrompts get() = error("Unexpected service")
    override val workflows: ScriptWorkflows get() = error("Unexpected service")
    override val data: ScriptData get() = error("Unexpected service")

    suspend fun call(call: ScriptUiCall): ScriptUiResult = checkNotNull(handler)(call)
}
