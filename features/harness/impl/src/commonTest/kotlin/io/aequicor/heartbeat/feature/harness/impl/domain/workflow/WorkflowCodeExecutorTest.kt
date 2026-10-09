package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.RuntimeTestCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

internal class WorkflowCodeExecutorTest {
    @Test
    fun `pinned source and input execute with captured origin and close the compiled lease`() = runTest {
        val fixture = CodeExecutionFixture(StandardTestDispatcher(testScheduler))
        val origin = HarnessCallOrigin(sendChain = mapOf(fixture.run.harness to 2))
        fixture.definition = { input ->
            assertEquals(origin, currentCoroutineContext()[HarnessOriginContext]?.origin)
            assertEquals(fixture.run.input, input)
            step("answer") { JsonPrimitive("done") }
        }
        assertEquals(JsonPrimitive("done"), fixture.execute(origin = origin))
        assertEquals(fixture.run.pinned.source, fixture.requests.single().source)
        assertEquals(1, fixture.artifact.closes)
        assertEquals(JsonPrimitive("done"), fixture.workflow.storage.load().single().steps.single().result)
    }

    @Test
    fun `timeout and author failure remain typed and release code only after execution drains`() = runTest {
        val fixture = CodeExecutionFixture(StandardTestDispatcher(testScheduler))
        fixture.definition = { awaitCancellation() }
        val short = fixture.run.copy(deadline = fixture.run.startedAt + 20.milliseconds)
        assertEquals(
            WorkflowFailure.Timeout,
            assertFailsWith<WorkflowStepFailed> { fixture.execute(short) }.reason,
        )
        assertEquals(1, fixture.artifact.closes)
        val failed = CodeExecutionFixture(StandardTestDispatcher(testScheduler))
        failed.definition = { error("private author exception") }
        assertEquals(WorkflowFailure.Error, assertFailsWith<WorkflowStepFailed> { failed.execute() }.reason)
        assertEquals(1, failed.artifact.closes)
    }

    @Test
    fun `caught duplicate registration is invalid and source mismatch never reaches the compiler`() = runTest {
        val fixture = CodeExecutionFixture(StandardTestDispatcher(testScheduler))
        fixture.afterRegister = {
            assertFailsWith<IllegalStateException> { it.register { JsonPrimitive("second") } }
        }
        assertEquals(WorkflowFailure.Error, assertFailsWith<WorkflowStepFailed> { fixture.execute() }.reason)
        assertEquals(1, fixture.artifact.closes)
        val mismatch = CodeExecutionFixture(StandardTestDispatcher(testScheduler))
        assertEquals(
            WorkflowFailure.Diverged,
            assertFailsWith<WorkflowStepFailed> {
                mismatch.execute(mismatch.run.copy(pinned = mismatch.run.pinned.copy(source = "changed")))
            }.reason,
        )
        assertEquals(0, mismatch.requests.size)
    }

    @Test
    fun `hook restricted origins cannot evaluate a workflow`() = runTest {
        val fixture = CodeExecutionFixture(StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { fixture.execute(origin = HarnessCallOrigin(isHookRestricted = true)) }
        assertEquals(0, fixture.requests.size)
    }
}

private class CodeExecutionFixture(dispatcher: CoroutineDispatcher) {
    val workflow = WorkflowFixture()
    val run = workflow.initial.copy(pinned = workflow.initial.pinned.let { it.copy(sourceSha = hash(it.source)) })
    val artifact = RuntimeTestCode()
    val requests = mutableListOf<HarnessCompilationRequest>()
    var definition: WorkflowDefinition = { JsonPrimitive("done") }
    var afterRegister: (WorkflowRegistration) -> Unit = {}
    private val host = object : HarnessScriptHost {
        override val isAvailable = true
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
            requests += request
            return HarnessCompilationResult.Success(artifact, emptyList())
        }
        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult {
            val registration = (context as HarnessEvaluationContext.Workflow).registration
            registration.register(definition)
            afterRegister(registration)
            return HarnessEvaluationResult.Success
        }
        override suspend fun removeCached(harness: HarnessId, item: ItemId): Unit = error("Pinned code stays owned")
    }
    private val origins = object : HarnessCallOrigins {
        override fun current(): HarnessCallOrigin = HarnessCallOrigin()
        override fun context(origin: HarnessCallOrigin): HarnessOriginContext = HarnessOriginContext(origin)
    }
    private val executor = WorkflowCodeExecutor(host, HarnessExecutionLane(dispatcher), origins, workflow.clock, ::hash)

    suspend fun execute(selected: WorkflowRun = run, origin: HarnessCallOrigin = HarnessCallOrigin()): JsonElement {
        workflow.storage.save(selected, null)
        val journal = WorkflowRunJournal(selected, workflow.storage) {}
        return executor.execute(
            selected,
            journal,
            WorkflowAgentSteps { _, _, _, _ -> error("No helper expected") },
            origin,
        )
    }
}

private fun hash(source: String): String = source.encodeUtf8().sha256().hex()
