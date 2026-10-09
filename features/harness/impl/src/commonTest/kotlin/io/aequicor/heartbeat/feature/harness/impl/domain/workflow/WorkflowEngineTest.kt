package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class WorkflowEngineTest {
    @Test
    fun `nested memo replay skips its subtree without shifting later sibling keys or clock values`() = runTest {
        val fixture = WorkflowFixture()
        var evaluations = 0
        val definition: WorkflowDefinition = {
            val first = step("outer") {
                evaluations++
                JsonPrimitive(agent("inside", AgentOptions("Helper")))
            }
            JsonArray(listOf(first, JsonPrimitive(now().toString())))
        }
        val initial = fixture.engine().execute(definition)
        fixture.clock.instant += 2.hours
        assertEquals(initial, fixture.engine().execute(definition))
        assertEquals(1, evaluations)
        assertEquals(1, fixture.requests.size)
        assertEquals(listOf("s0", "p0/s0", "s1"), fixture.storage.load().single().steps.map { it.key.value })
    }

    @Test
    fun `parallel branches retain declaration order and separate successive groups`() = runTest {
        val fixture = WorkflowFixture()
        val secondFinished = CompletableDeferred<Unit>()
        val definition: WorkflowDefinition = {
            val first = parallel(
                {
                    step("slow") {
                        secondFinished.await()
                        JsonPrimitive("first")
                    }
                },
                {
                    step("fast") {
                        secondFinished.complete(Unit)
                        JsonPrimitive("second")
                    }
                },
            )
            val next = parallel({ step("next") { JsonPrimitive("third") } })
            JsonArray(first + next)
        }
        val result = JsonArray(listOf("first", "second", "third").map(::JsonPrimitive))
        assertEquals(result, fixture.engine().execute(definition))
        assertEquals(result, fixture.engine().execute(definition))
        val steps = fixture.storage.load().single().steps
        assertEquals(setOf("s0", "p0/p0/s0", "p0/p1/s0", "s1", "p1/p0/s0"), steps.map { it.key.value }.toSet())
        assertTrue(steps.all { it.phase == StepPhase.Completed })
    }

    @Test
    fun `changed prompt and omitted recorded suffix are divergent even when author catches mismatch`() = runTest {
        val fixture = WorkflowFixture()
        fixture.engine().execute {
            agent("original", AgentOptions("Helper"))
            step("suffix") { JsonNull }
        }
        val changed = assertFailsWith<WorkflowStepFailed> {
            fixture.engine().execute {
                try {
                    agent("changed", AgentOptions("Helper"))
                } catch (failure: WorkflowStepFailed) {
                    assertEquals(WorkflowFailure.Diverged, failure.reason)
                }
                JsonNull
            }
        }
        assertEquals(WorkflowFailure.Diverged, changed.reason)
        val shortened = assertFailsWith<WorkflowStepFailed> {
            fixture.engine().execute { JsonPrimitive(agent("original", AgentOptions("Helper"))) }
        }
        assertEquals(WorkflowFailure.Diverged, shortened.reason)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `step failures replay with the same typed reason and no repeated body`() = runTest {
        val fixture = WorkflowFixture()
        var evaluations = 0
        val definition: WorkflowDefinition = {
            try {
                step("fails") {
                    evaluations++
                    throw WorkflowStepFailed(WorkflowFailure.Timeout)
                }
            } catch (failure: WorkflowStepFailed) {
                assertEquals(WorkflowFailure.Timeout, failure.reason)
                JsonPrimitive("recovered")
            }
        }
        assertEquals(JsonPrimitive("recovered"), fixture.engine().execute(definition))
        assertEquals(JsonPrimitive("recovered"), fixture.engine().execute(definition))
        assertEquals(1, evaluations)
        assertEquals(WorkflowFailure.Timeout, fixture.storage.load().single().steps.single().failure)
    }

    @Test
    fun `author errors are sanitized and cancellation leaves prepared work recoverable`() = runTest {
        val fixture = WorkflowFixture()
        val error = assertFailsWith<WorkflowStepFailed> {
            fixture.engine().execute { step("bad") { error("SECRET") } }
        }
        assertEquals(WorkflowFailure.Error, error.reason)
        assertFalse(error.toString().contains("SECRET"))
        assertEquals(null, error.cause)
        val interrupted = WorkflowFixture()
        assertFailsWith<CancellationException> {
            interrupted.engine().execute { step("pause") { throw CancellationException("stop") } }
        }
        assertEquals(StepPhase.Prepared, interrupted.storage.load().single().steps.single().phase)
        assertEquals(JsonNull, interrupted.engine().execute { step("pause") { JsonNull } })
    }

    @Test
    fun `pinned skills and literal template arguments do not consult the current library`() = runTest {
        val fixture = WorkflowFixture()
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Pinned skill"), JsonPrimitive("Hello {{literal}}"))),
            fixture.engine().execute {
                JsonArray(
                    listOf(
                        JsonPrimitive(skill(ItemName("guide"))),
                        JsonPrimitive(prompt(ItemName("greeting"), mapOf("name" to "{{literal}}"))),
                    ),
                )
            },
        )
    }
}
