package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowScope
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.content.renderHarnessTemplate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.capturePlatformHarnessFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Compiler-independent replay interpreter. Keys encode declaration positions, never completion order. A nested
 * memo owns a child path; replaying it skips that entire recorded subtree, so later sibling keys stay stable.
 * Parallel groups also reserve a structural position, preventing collisions between successive parallel calls.
 * All reads use pinned content. The driver owns deadlines, native cleanup, code execution lanes and completion.
 */
internal class WorkflowEngine(
    private val run: WorkflowRun,
    private val journal: WorkflowStepJournal,
    private val agents: WorkflowAgentSteps,
    private val clock: Clock,
    private val digest: (String) -> String,
) : WorkflowScope {
    private val visited = MutableStateFlow<Set<StepKey>>(emptySet())
    private val diverged = MutableStateFlow(false)
    private val started = MutableStateFlow(false)
    private val log = Log.tag("HarnessWorkflow")
    override val input: JsonObject get() = run.input

    suspend fun execute(definition: WorkflowDefinition): JsonElement {
        check(started.compareAndSet(false, true)) { "A replay engine executes once" }
        val result = withContext(WorkflowFrame(this, "")) { author { definition(input) } }
        if (diverged.value || journal.steps.any { it.key !in visited.value }) divergence()
        return bounded(result)
    }

    override suspend fun agent(prompt: String, options: AgentOptions): String = position { place ->
        val hash = hash("agent", prompt, options.title, options.target?.let { Json.encodeToString(it) })
        val old = existing(place.key, hash)
        if (old?.phase == StepPhase.Completed) return@position stringResult(old)
        if (old?.phase == StepPhase.Failed) throw WorkflowStepFailed(checkNotNull(old.failure))
        agents.execute(place.key, hash, prompt, options).also {
            val saved = existing(place.key, hash)
            if (saved?.phase != StepPhase.Completed || stringResult(saved) != it) {
                log.w(IllegalStateException("Helper result is not durable")) { "Workflow helper journal unavailable" }
                throw WorkflowExecutionUnavailable()
            }
        }
    }

    override suspend fun step(name: String, body: suspend () -> JsonElement): JsonElement = position { place ->
        require(name.isNotBlank() && name.length <= MAX_STEP_NAME) { "Invalid workflow step name" }
        memo(place, hash("step", name), body)
    }

    override suspend fun now(): Instant = position { place ->
        val value = memo(place, hash("clock")) { JsonPrimitive(clock.now().toString()) }
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: divergence()
        Instant.parse(text)
    }

    override suspend fun parallel(vararg branches: suspend WorkflowScope.() -> JsonElement): List<JsonElement> =
        position { place ->
            require(branches.size <= HarnessLimits.STEPS) { "Too many workflow branches" }
            val hash = hash("parallel", branches.size.toString())
            val old = existing(place.key, hash)
            if (old?.phase == StepPhase.Failed) {
                visitChildren(place)
                throw WorkflowStepFailed(checkNotNull(old.failure))
            }
            journal.prepare(place.key, hash)
            val values = recordFailure(place.key) {
                coroutineScope {
                    branches.mapIndexed { index, branch ->
                        async(WorkflowFrame(this@WorkflowEngine, "${place.children}p$index/")) {
                            author { branch(this@WorkflowEngine) }
                        }
                    }.awaitAll()
                }
            }
            // The marker records only structure; child journals own results, avoiding an oversized aggregate.
            if (old?.phase != StepPhase.Completed) journal.complete(place.key, JsonPrimitive("parallel"))
            values
        }

    override fun prompt(name: ItemName, args: Map<String, String>): String {
        val body = skill(name)
        val arguments = Regex("\\{\\{([^{}]+)}}").findAll(body).map { it.groupValues[1] }.toSet()
        return renderHarnessTemplate(HarnessItem.Template(ItemId("pinned"), name, "", body, arguments), args)
    }

    override fun skill(name: ItemName): String = checkNotNull(run.pinned.renderedItems[name]) {
        "Pinned item is unavailable"
    }

    private suspend fun memo(place: WorkflowPosition, hash: String, body: suspend () -> JsonElement): JsonElement {
        val old = existing(place.key, hash)
        if (old?.phase == StepPhase.Completed || old?.phase == StepPhase.Failed) {
            visitChildren(place)
            if (old.phase == StepPhase.Failed) throw WorkflowStepFailed(checkNotNull(old.failure))
            return checkNotNull(old.result)
        }
        journal.prepare(place.key, hash)
        val result = recordFailure(place.key) {
            withContext(WorkflowFrame(this, place.children)) { bounded(author(body)) }
        }
        journal.complete(place.key, result)
        return result
    }

    private suspend fun <T> position(block: suspend (WorkflowPosition) -> T): T {
        val frame = currentCoroutineContext()[WorkflowFrame]?.takeIf { it.owner === this } ?: divergence()
        val place = frame.enter() ?: divergence()
        return try {
            visited.update { it + place.key }
            if (visited.value.size > HarnessLimits.STEPS) throw WorkflowStepFailed(WorkflowFailure.Error)
            block(place)
        } finally {
            frame.leave()
        }
    }

    private fun existing(key: StepKey, hash: String): WorkflowStep? = journal.steps.find { it.key == key }?.also {
        if (it.promptSha != hash) divergence()
    }

    private fun visitChildren(place: WorkflowPosition) {
        visited.update { keys -> keys + journal.steps.filter { it.key.value.startsWith(place.children) }.map { it.key } }
    }

    private suspend fun <T> recordFailure(key: StepKey, body: suspend () -> T): T = try {
        body()
    } catch (failure: WorkflowStepFailed) {
        if (failure.reason == WorkflowFailure.Diverged) divergence()
        journal.fail(key, failure.reason)
        throw failure
    }

    private suspend fun <T> author(body: suspend () -> T): T = capturePlatformHarnessFailure(body).getOrElse { error ->
        if (error is WorkflowStepFailed) throw error
        log.w(harnessScriptFailure(error)) { "Workflow author code failed" }
        throw WorkflowStepFailed(WorkflowFailure.Error)
    }

    private fun bounded(value: JsonElement): JsonElement {
        if (value.toString().length > HarnessLimits.RESULT_CHARS) throw WorkflowStepFailed(WorkflowFailure.Error)
        return value
    }

    private fun stringResult(step: WorkflowStep): String =
        (step.result as? JsonPrimitive)?.takeIf { it.isString }?.content ?: divergence()

    private fun hash(vararg values: String?): String = digest(Json.encodeToString(values.toList()))

    private fun divergence(): Nothing {
        diverged.value = true
        throw WorkflowStepFailed(WorkflowFailure.Diverged)
    }
}

private const val MAX_STEP_NAME = 256
