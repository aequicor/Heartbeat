package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ChoiceSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.InheritReason
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionOption
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentComplexity
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentComplexity
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentNeed
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentPriority
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperKind
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModel
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModelRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdaptiveHarnessHelperModelsTest {
    @Test
    fun `workflow request maps every hint and a selection becomes the helper model`() = runTest {
        val models = RecordingModels(
            ModelChoice.Selected(TARGET, "high", "Model", Ratings.NEUTRAL, "Best fit", ChoiceSource.Router, 1),
        )
        val adaptive = AdaptiveAgent(
            AgentPriority.Speed,
            AgentComplexity.High,
            setOf(AgentNeed.Images, AgentNeed.LongContext),
            setOf("Coding"),
        )
        val workspace = WorkspaceRef("project")
        val chosen = AdaptiveHarnessHelperModels(lazyOf(models))
            .choose(HarnessHelperModelRequest("Task", adaptive, HarnessHelperKind.Workflow, workspace))
        assertEquals(HarnessHelperModel(TARGET, "high"), chosen)
        val expected = SubagentRequest(
            "Task",
            SubagentPriority.Speed,
            SubagentComplexity.High,
            setOf(SubagentNeed.Images, SubagentNeed.LongContext),
            setOf("coding"),
        )
        assertEquals(expected, models.requests.single())
        assertEquals(SelectionContext(SubagentHost.WorkflowHelper, null, workspace), models.contexts.single())
    }

    @Test
    fun `script helper without a priority inherits when nothing fits`() = runTest {
        val models = RecordingModels(ModelChoice.Inherit(InheritReason.NoCandidates))
        val chosen = AdaptiveHarnessHelperModels(lazyOf(models))
            .choose(HarnessHelperModelRequest("Task", AdaptiveAgent(), HarnessHelperKind.Script, null))
        assertNull(chosen)
        assertNull(models.requests.single().priority)
        assertEquals(SubagentComplexity.Medium, models.requests.single().complexity)
        assertEquals(SubagentHost.ScriptHelper, models.contexts.single().host)
    }
}

private class RecordingModels(private val choice: ModelChoice) : AdaptiveModels {
    val requests = mutableListOf<SubagentRequest>()
    val contexts = mutableListOf<SelectionContext>()

    override suspend fun select(request: SubagentRequest, context: SelectionContext): ModelChoice {
        requests += request
        contexts += context
        return choice
    }

    override suspend fun preview(request: SubagentRequest, context: SelectionContext): List<SelectionOption> =
        emptyList()
}

private val TARGET = EngineTarget(EngineId("claude"), EngineBindingId("cli"), ModelId("sonnet"))
