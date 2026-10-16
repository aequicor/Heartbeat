package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentPriority
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperKind
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModel
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperModelRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestCreated
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class WorkflowAdaptiveHelperTest {
    @Test
    fun `adaptive step creates its helper on the chosen model before acquiring a slot`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val requests = mutableListOf<HarnessHelperModelRequest>()
        fixture.models = { request ->
            requests += request
            fixture.trace += "choose"
            HarnessHelperModel(CHOSEN, "low")
        }
        val adaptive = AdaptiveAgent(AgentPriority.Cost, tags = setOf("review"))
        val options = AgentOptions("Helper", adaptive = adaptive)
        assertEquals("Done", fixture.driver(fixture.workflow.journal()).execute(STEP, SHA, "Task", options))
        assertEquals(listOf("choose", "acquire", "create"), fixture.trace.take(3))
        assertEquals(listOf(SpawnTestCreated(CHOSEN, HelperModelOptions("low"))), fixture.base.created)
        val request = requests.single()
        assertEquals("Task", request.task)
        assertEquals(adaptive, request.adaptive)
        assertEquals(HarnessHelperKind.Workflow, request.kind)
    }

    @Test
    fun `explicit target wins over the adaptive request`() = runTest {
        val fixture = WorkflowHelpersFixture()
        var choices = 0
        fixture.models = {
            choices++
            HarnessHelperModel(CHOSEN, "low")
        }
        val options = AgentOptions("Helper", PINNED, AdaptiveAgent(AgentPriority.Speed))
        fixture.driver(fixture.workflow.journal()).execute(STEP, SHA, "Task", options)
        assertEquals(0, choices)
        assertEquals(listOf(SpawnTestCreated(PINNED, HelperModelOptions())), fixture.base.created)
    }

    @Test
    fun `restored step keeps its helper and never chooses again`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        fixture.restore(progress)
        var choices = 0
        fixture.models = {
            choices++
            HarnessHelperModel(CHOSEN, "low")
        }
        val options = AgentOptions("Helper", adaptive = AdaptiveAgent(AgentPriority.Quality))
        assertEquals("Done", fixture.driver(progress).execute(STEP, SHA, "Task", options))
        assertEquals(0, choices)
        assertTrue(fixture.base.created.isEmpty())
    }
}

private val STEP = StepKey("s0")
private val SHA = "a".repeat(64)
private val CHOSEN = EngineTarget(EngineId("codex"), EngineBindingId("work"), ModelId("mini"))
private val PINNED = EngineTarget(EngineId("claude"), EngineBindingId("cli"), ModelId("opus"))
