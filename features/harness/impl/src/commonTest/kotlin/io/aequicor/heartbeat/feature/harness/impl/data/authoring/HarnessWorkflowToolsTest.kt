package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.now
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class HarnessWorkflowToolsTest {
    private val run = WorkflowRun(
        RunId("wf_1"), harness.id, harness.items.first().id,
        PinnedWorkflow("workflow { it }", "0".repeat(64), 0, emptyMap()),
        JsonObject(emptyMap()), dispatchSession, WorkflowOrigin.Agent, now, now + 1.hours,
        status = WorkflowStatus.Completed(JsonPrimitive("done")), finishedAt = now,
    )
    private val runs = FakeRuns(HarnessRunsState.Ready(listOf(run)))
    private val tools = HarnessWorkflowTools(
        EnabledToggles,
        lazy { error("library is not used") },
        lazy { error("launches are not used") },
        lazyOf(runs),
        lazy { error("helpers are not used") },
        lazy { error("origins are not used") },
    )

    @Test
    fun `status is visible only to the session that started the run`() = runTest {
        val own = tools.execute(context(dispatchSession), HarnessTools.WORKFLOW_STATUS, runArgument("wf_1"))
        assertTrue(own.text.contains("completed") && own.text.contains("\"done\""), own.text)
        val other = SessionRef(EngineId("engine"), SessionSourceId("source"), "other")
        val foreign = tools.execute(context(other), HarnessTools.WORKFLOW_STATUS, runArgument("wf_1"))
        assertTrue(foreign.isError)
        assertTrue(!foreign.text.contains("done"))
        assertTrue(tools.execute(context(dispatchSession), HarnessTools.WORKFLOW_STATUS, runArgument("../x")).isError)
    }

    @Test
    fun `cancel sends the agent viewer and refuses finished runs`() = runTest {
        assertTrue(tools.execute(context(dispatchSession), HarnessTools.WORKFLOW_CANCEL, runArgument("wf_1")).isError)
        runs.state.value = HarnessRunsState.Ready(
            listOf(run.copy(status = WorkflowStatus.Running, finishedAt = null)),
        )
        tools.execute(context(dispatchSession), HarnessTools.WORKFLOW_CANCEL, runArgument("wf_1"))
        val cancel = runs.sent.single() as HarnessRunsIntent.Public.Cancel
        assertEquals(WorkflowViewer.Agent(dispatchSession), cancel.by)
        assertEquals(run.id, cancel.run)
    }

    private fun context(session: SessionRef) = AgentToolContext(session, null, TurnId("turn"))

    private fun runArgument(id: String) = buildJsonObject { put("run", id) }
}

private object EnabledToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Only the boolean feature flag is read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == HarnessEnabled)
        return flowOf(true) as Flow<T>
    }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class FakeRuns(initial: HarnessRunsState) : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()
    val sent = mutableListOf<HarnessRunsIntent>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}
