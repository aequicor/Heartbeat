package io.aequicor.heartbeat.feature.harness.impl.data.run

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRouting
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowLibraryEpoch
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class MachineWorkflowLifecycleTest {
    private val runs = LifecycleRuns()
    private val library = LifecycleLibrary()
    private val lifecycle = MachineWorkflowLifecycle(
        lazy { runs },
        lazy { library },
        lazy { error("Stopping never pauses drivers") },
        WorkflowLibraryEpoch(EPOCH),
    )

    @Test
    fun `stopping cancels runs of fenced and earlier-process generations but not newer admissions`() = runTest {
        val fenced = run("wf_fenced", EPOCH, generation = 3)
        val earlierProcess = run("wf_restored", "previous", generation = 99)
        val newer = run("wf_newer", EPOCH, generation = 4)
        val foreign = run("wf_foreign", EPOCH, generation = 1, harness = HarnessId("other"))
        runs.state.value = HarnessRunsState.Ready(listOf(fenced, earlierProcess, newer, foreign))
        assertFalse(lifecycle.deactivate(stop(generation = 3)))
        val cancelled = runs.sent.filterIsInstance<HarnessRunsIntent.Internal.CancelRun>()
        assertEquals(listOf(fenced.id, earlierProcess.id), cancelled.map { it.run })
        assertTrue(cancelled.all { it.by == WorkflowViewer.User })
        // Once the fenced runs are terminal the barrier is confirmed, even with the newer run still active.
        runs.state.value = HarnessRunsState.Ready(listOf(newer, foreign))
        assertTrue(lifecycle.deactivate(stop(generation = 3)))
    }

    @Test
    fun `absent journal is restored first and the barrier stays unconfirmed`() = runTest {
        assertFalse(lifecycle.deactivate(stop(generation = 3)))
        assertEquals(listOf<HarnessRunsIntent>(HarnessRunsIntent.Internal.Start), runs.sent)
    }

    @Test
    fun `pausing an unsuspended library or an item-only effect needs no workflow barrier`() = runTest {
        val pause = HarnessEffect.Deactivate(emptyList(), false, setOf(HARNESS), 3)
        assertTrue(lifecycle.deactivate(pause))
        assertTrue(lifecycle.deactivate(HarnessEffect.Deactivate(emptyList(), true, emptySet(), 3)))
        assertTrue(runs.sent.isEmpty())
    }
}

private fun stop(generation: Long) = HarnessEffect.Deactivate(emptyList(), true, setOf(HARNESS), generation)

private fun run(id: String, epoch: String, generation: Long, harness: HarnessId = HARNESS) = WorkflowRun(
    RunId(id), harness, ItemId("flow"),
    PinnedWorkflow("workflow { it }", "a".repeat(64), 1, emptyMap()), JsonObject(emptyMap()), null,
    WorkflowOrigin.Script, AT, AT + 1.hours,
    routing = WorkflowRouting(null, HelperHandoff(), epoch, generation),
)

private class LifecycleRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Idle())
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()
    val sent = mutableListOf<HarnessRunsIntent>()
    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private class LifecycleLibrary : HarnessMachine {
    override val name = "harness"
    override val state = MutableStateFlow<HarnessState>(HarnessState.Ready(isRuntimeAvailable = true))
    override val outputs = MutableSharedFlow<HarnessOutput>()
    override suspend fun send(intent: HarnessIntent): SendResult = SendResult.Ignored
}

private const val EPOCH = "epoch"
private val HARNESS = HarnessId("harness")
private val AT = Instant.fromEpochMilliseconds(1_000)
