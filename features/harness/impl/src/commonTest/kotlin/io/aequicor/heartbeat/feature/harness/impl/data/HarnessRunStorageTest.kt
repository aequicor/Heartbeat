package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.data.run.KeyValueHarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class HarnessRunStorageTest {
    private val clock = RunDeliveryClock()
    private val kv = RunDeliveryTestStore(clock)
    private fun repository() = KeyValueHarnessRunStorage(kv, clock)
    private fun run(id: String = "wf_one", harness: String = "harness") = WorkflowRun(
        RunId(id), HarnessId(harness), ItemId("workflow"),
        PinnedWorkflow("private-source", "a".repeat(64), 1, emptyMap()), JsonObject(emptyMap()), null,
        WorkflowOrigin.Script, clock.now(), clock.now() + 1.hours,
    )

    @Test
    fun `raw access failures hide messages while cancellation remains cancellation`() = runTest {
        kv.readFailureKey = "index"
        val error = assertFailsWith<HarnessStorageUncertain> { repository().load() }
        assertFalse(error.toString().contains("private-store-message"))
        assertNull(error.cause)
        kv.readFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { repository().load() }
    }

    @Test
    fun `running journals survive restart without retention and exact retries do not write`() = runTest {
        val run = run()
        assertTrue(repository().save(run, null))
        val writes = kv.writes
        assertTrue(repository().save(run, null))
        assertEquals(writes, kv.writes)
        assertEquals(Retention.Permanent, kv.retention["run.wf_one"])
        clock.instant += 31.days
        assertEquals(listOf(run), repository().load())
    }

    @Test
    fun `terminal expiry is anchored to finish and missing expired value repairs only its index`() = runTest {
        val running = run()
        repository().save(running, null)
        val terminal = running.copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = clock.now())
        repository().save(terminal, 0)
        val expiry = Retention.expiring(Expiry.At(clock.now() + 30.days))
        assertEquals(expiry, kv.retention["run.wf_one"])
        clock.instant += 1.days
        assertTrue(repository().save(terminal, 0))
        assertEquals(expiry, kv.retention["run.wf_one"])
        clock.instant += 30.days
        assertTrue(repository().load().isEmpty())
        assertFalse((kv.values["index"] as String).contains("wf_one"))
        assertFalse(repository().save(running, 0))
    }

    @Test
    fun `retention caps only terminal records per harness and preserves every running journal`() = runTest {
        val live = run("wf_live")
        repository().save(live, null)
        repeat(21) { number ->
            val completed = run(
                "wf_$number",
            ).copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = clock.now())
            repository().save(completed, null)
            clock.instant += 1.seconds
        }
        val other = run(
            "wf_other",
            "another",
        ).copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = clock.now())
        repository().save(other, null)
        val restored = repository().load()
        assertEquals(22, restored.size)
        assertTrue(live in restored && other in restored)
        assertFalse(restored.any { it.id == RunId("wf_0") })
    }

    @Test
    fun `missing running and unexpired terminal records fail closed without exposing source`() = runTest {
        val original = run()
        repository().save(original, null)
        kv.values.remove("run.wf_one")
        assertFailsWith<HarnessStorageCorrupt> { repository().load() }
        kv.values["run.wf_one"] = "private-source-corrupt-json"
        val error = assertFailsWith<HarnessStorageCorrupt> { repository().load() }
        assertFalse(error.toString().contains("private-source"))
        assertNull(error.cause)
        kv.values["run.wf_one"] = Json.encodeToString(original)
        repository().save(original.copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = clock.now()), 0)
        kv.values.remove("run.wf_one")
        assertFailsWith<HarnessStorageCorrupt> { repository().load() }
    }

    @Test
    fun `generation comparison fences old drivers and terminal outcomes never reopen`() = runTest {
        val original = run()
        repository().save(original, null)
        val next = original.copy(driverGeneration = 1)
        assertTrue(repository().save(next, 0))
        assertFalse(repository().save(original.copy(attempt = 1), 0))
        val terminal = next.copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = clock.now())
        assertTrue(repository().save(terminal, 1))
        assertFalse(repository().save(next, 1))
        assertEquals(listOf(terminal), repository().load())
    }

    @Test
    fun `parallel stale snapshot cannot discard another branch or rewrite a terminal memo`() = runTest {
        val first = WorkflowStep(StepKey("p0/s0"), "c".repeat(64), request = RequestId("first"))
        val second = WorkflowStep(StepKey("p1/s0"), "d".repeat(64), request = RequestId("second"))
        val original = run()
        repository().save(original, null)
        val branchOne = original.copy(steps = listOf(first))
        assertTrue(repository().save(branchOne, 0))
        assertFalse(repository().save(original.copy(steps = listOf(second)), 0))
        val both = branchOne.copy(steps = listOf(first, second))
        assertTrue(repository().save(both, 0))
        val memo = first.copy(phase = StepPhase.Completed, result = JsonNull)
        val completed = both.copy(steps = listOf(memo, second))
        assertTrue(repository().save(completed, 0))
        assertFalse(repository().save(both, 0))
        assertFalse(repository().save(completed.copy(steps = listOf(memo.copy(promptSha = "e".repeat(64)), second)), 0))
        assertEquals(listOf(completed), repository().load())
    }

    @Test
    fun `cancellation and recovery attempts are monotonic and retry changes request exactly once`() = runTest {
        val prepared = WorkflowStep(StepKey("s0"), "c".repeat(64), request = RequestId("first"))
        val original = run().copy(steps = listOf(prepared))
        repository().save(original, null)
        val running = original.copy(steps = listOf(prepared.copy(phase = StepPhase.Running)))
        assertTrue(repository().save(running, 0))
        assertFalse(repository().save(original, 0))
        val recovery = prepared.copy(attempt = 1, request = RequestId("retry"))
        assertFalse(repository().save(running.copy(steps = listOf(recovery.copy(request = prepared.request))), 0))
        val resumed = running.copy(attempt = 1, steps = listOf(recovery))
        assertTrue(repository().save(resumed, 0))
        assertFalse(repository().save(resumed.copy(attempt = 0), 0))
        val cancelling = resumed.copy(cancellation = WorkflowFailure.Cancelled)
        assertTrue(repository().save(cancelling, 0))
        assertFalse(repository().save(resumed, 0))
        assertFalse(repository().save(cancelling.copy(cancellation = WorkflowFailure.Timeout), 0))
        assertEquals(listOf(cancelling), repository().load())
    }

    @Test
    fun `restart rolls back prepared creation but discovers running after committed acknowledgement loss`() = runTest {
        val original = run()
        kv.crashAfterWrite = { key, _ -> key == "run.wf_one" }
        assertFailsWith<HarnessStorageUncertain> { repository().save(original, null) }
        kv.isUnavailable = false
        kv.crashAfterWrite = null
        assertTrue(repository().load().isEmpty())
        kv.crashAfterWrite = { key, value -> key == "pending" && value.toString().contains("Committed") }
        assertFailsWith<HarnessStorageUncertain> { repository().save(original, null) }
        kv.isUnavailable = false
        kv.crashAfterWrite = null
        assertEquals(listOf(original), repository().load())
        assertTrue(repository().save(original, null))
    }
}
