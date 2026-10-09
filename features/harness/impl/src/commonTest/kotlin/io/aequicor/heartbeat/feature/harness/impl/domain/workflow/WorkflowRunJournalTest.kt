package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowRunJournalTest {
    @Test
    fun `uncertain terminal commit retries the identical result without reexecuting author body`() = runTest {
        val fixture = WorkflowFixture()
        fixture.storage.save(fixture.initial, null)
        var uncertain = true
        val storage = object : HarnessRunStorage by fixture.storage {
            override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean {
                val saved = fixture.storage.save(run, expectedGeneration)
                if (run.steps.any { it.phase == StepPhase.Completed } && uncertain) {
                    uncertain = false
                    throw HarnessStorageUncertain()
                }
                return saved
            }
        }
        val feedback = mutableListOf<WorkflowStep>()
        val journal = WorkflowRunJournal(fixture.initial, storage) { feedback += it }
        val engine = WorkflowEngine(
            fixture.initial, journal, WorkflowAgentSteps { _, _, _, _ -> error("No helper") }, fixture.clock,
        ) { it.encodeUtf8().sha256().hex() }
        var calls = 0
        val running = async {
            engine.execute {
                step("memo") {
                    calls++
                    JsonPrimitive("result")
                }
            }
        }
        runCurrent()
        assertEquals(listOf(StepPhase.Prepared), feedback.map { it.phase })
        assertEquals(1, calls)
        advanceTimeBy(250)
        assertEquals(JsonPrimitive("result"), running.await())
        assertEquals(listOf(StepPhase.Prepared, StepPhase.Completed), feedback.map { it.phase })
        assertEquals(1, calls)
    }

    @Test
    fun `parallel writes preserve every branch and feedback follows confirmed persistence`() = runTest {
        val fixture = WorkflowFixture()
        val journal = fixture.journal()
        (0..7).map { branch ->
            async {
                val key = StepKey("p$branch/s0")
                journal.prepare(key, "a".repeat(64))
                journal.complete(key, JsonPrimitive(branch))
            }
        }.awaitAll()
        assertEquals(8, fixture.storage.load().single().steps.size)
        assertTrue(fixture.storage.load().single().steps.all { it.phase == StepPhase.Completed })
        assertEquals(journal.steps, fixture.storage.load().single().steps)
    }

    @Test
    fun `revoked generation cannot publish a result or turn stale storage into a memoized author failure`() = runTest {
        val fixture = WorkflowFixture()
        val journal = fixture.journal()
        val key = StepKey("s0")
        journal.prepare(key, "a".repeat(64))
        val newer = fixture.storage.load().single().copy(driverGeneration = 1)
        assertTrue(fixture.storage.save(newer, 0))
        assertFailsWith<WorkflowExecutionUnavailable> { journal.complete(key, JsonNull) }
        assertEquals(newer, fixture.storage.load().single())
        assertEquals(listOf(StepPhase.Prepared), fixture.feedback.map { it.phase })
    }

    @Test
    fun `prepared feedback cannot escape while the durable write is pending`() = runTest {
        val fixture = WorkflowFixture()
        fixture.storage.save(fixture.initial, null)
        val release = CompletableDeferred<Unit>()
        val feedback = mutableListOf<WorkflowStep>()
        val storage = object : HarnessRunStorage by fixture.storage {
            override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean {
                release.await()
                return fixture.storage.save(run, expectedGeneration)
            }
        }
        val journal = WorkflowRunJournal(fixture.initial, storage) { feedback += it }
        val waiting = async { journal.prepare(StepKey("s0"), "a".repeat(64)) }
        runCurrent()
        assertTrue(feedback.isEmpty())
        assertTrue(journal.steps.isEmpty())
        release.complete(Unit)
        waiting.await()
        assertEquals(fixture.storage.load().single().steps, feedback)
    }
}
