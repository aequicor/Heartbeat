package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowScope
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal suspend fun evaluateProbeScript(host: HarnessScriptHost, code: CompiledHarnessCode, parent: CoroutineScope) {
    val owner = SupervisorJob(parent.coroutineContext[Job])
    val fixture = ProbeScriptScope(CoroutineScope(parent.coroutineContext + owner))
    try {
        withTimeout(10.seconds) {
            checkProbeEvaluation(host, code, HarnessEvaluationContext.Script(fixture))
            fixture.rendered.await()
            owner.children.toList().joinAll()
            checkProbe(fixture.calls.get() == 1, "ScriptCallback")
        }
    } finally {
        withContext(NonCancellable) { owner.cancelAndJoin() }
    }
}

internal suspend fun evaluateProbeWorkflow(host: HarnessScriptHost, code: CompiledHarnessCode) {
    var definition: WorkflowDefinition? = null
    val registration = WorkflowRegistration { next ->
        checkProbe(definition == null, "DuplicateWorkflow")
        definition = next
    }
    checkProbeEvaluation(host, code, HarnessEvaluationContext.Workflow(registration))
    val body = definition ?: throw HarnessProbeFailure("WorkflowRegistration")
    val fixture = ProbeWorkflowScope()
    checkProbe(fixture.steps == 0, "PrematureWorkflowExecution")
    val result = withTimeout(10.seconds) { body(fixture, fixture.input) }
    checkProbe(result == JsonPrimitive("workflow-ok") && fixture.steps == 1, "WorkflowBody")
}

private class ProbeScriptScope(override val scope: CoroutineScope) : HarnessScriptScope {
    override val harness = HarnessId("probe")
    override val name = HarnessName("probe")
    override val item = ItemId("script")
    override val revision = 1L
    val rendered = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    override val prompts: ScriptPrompts = object : ScriptPrompts {
        override suspend fun render(name: ItemName, args: Map<String, String>): String {
            checkProbe(
                name == ItemName("probe") && args == mapOf("item" to "script", "name" to "probe"),
                "ScriptContext",
            )
            calls.incrementAndGet()
            rendered.complete(Unit)
            return "script-ok"
        }
    }
    override val events: ScriptEvents get() = unexpectedProbeService()
    override val hooks: ScriptHooks get() = unexpectedProbeService()
    override val sessions: ScriptSessions get() = unexpectedProbeService()
    override val scheduler: ScriptScheduler get() = unexpectedProbeService()
    override val agent: ScriptAgent get() = unexpectedProbeService()
    override val workflows: ScriptWorkflows get() = unexpectedProbeService()
}

private class ProbeWorkflowScope : WorkflowScope {
    override val input = JsonObject(mapOf("probe" to JsonPrimitive("input")))
    var steps = 0
    override suspend fun step(name: String, body: suspend () -> JsonElement): JsonElement {
        checkProbe(name == "probe", "WorkflowStep")
        steps++
        return body()
    }
    override suspend fun agent(prompt: String, options: AgentOptions): String = unexpectedProbeService()
    override suspend fun parallel(vararg branches: suspend WorkflowScope.() -> JsonElement): List<JsonElement> =
        unexpectedProbeService()
    override suspend fun now(): Instant = unexpectedProbeService()
    override fun prompt(name: ItemName, args: Map<String, String>): String = unexpectedProbeService()
    override fun skill(name: ItemName): String = unexpectedProbeService()
}

private fun unexpectedProbeService(): Nothing = throw HarnessProbeFailure("UnexpectedService")
