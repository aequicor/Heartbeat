package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentComplexity
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HarnessAdaptiveSpawnTest {
    @Test
    fun `adaptive spawn chooses the model before acquiring a slot`() = runTest {
        val requests = mutableListOf<HarnessHelperModelRequest>()
        lateinit var fixture: HarnessSpawnFixture
        fixture = HarnessSpawnFixture(backgroundScope) { request ->
            requests += request
            fixture.trace += "choose"
            HarnessHelperModel(CHOSEN, "high")
        }
        val adaptive = AdaptiveAgent(complexity = AgentComplexity.High)
        fixture.operations.spawn(fixture.submission(adaptive = adaptive))
        assertEquals(listOf("choose", "acquire"), fixture.trace.take(2))
        assertEquals(listOf(SpawnTestCreated(CHOSEN, HelperModelOptions("high"))), fixture.helpers.created)
        val expected = HarnessHelperModelRequest("Private prompt", adaptive, HarnessHelperKind.Script, null)
        assertEquals(expected, requests.single())
    }

    @Test
    fun `plain spawn and inherited choice keep the default model`() = runTest {
        var choices = 0
        val counting = HarnessHelperModels {
            choices++
            null
        }
        val plain = HarnessSpawnFixture(backgroundScope, counting)
        plain.operations.spawn(plain.submission())
        assertEquals(0, choices)
        val inherited = HarnessSpawnFixture(backgroundScope, counting)
        inherited.operations.spawn(inherited.submission(adaptive = AdaptiveAgent()))
        assertEquals(1, choices)
        assertEquals(listOf(SpawnTestCreated(null, HelperModelOptions())), plain.helpers.created)
        assertEquals(listOf(SpawnTestCreated(null, HelperModelOptions())), inherited.helpers.created)
    }

    @Test
    fun `admission revoked during the choice never acquires a slot`() = runTest {
        lateinit var fixture: HarnessSpawnFixture
        fixture = HarnessSpawnFixture(backgroundScope) {
            fixture.isAdmitted = false
            HarnessHelperModel(CHOSEN, null)
        }
        assertFailsWith<IllegalStateException> {
            fixture.operations.spawn(fixture.submission(adaptive = AdaptiveAgent()))
        }
        assertEquals(emptyList(), fixture.helpers.acquisitions)
    }
}

private val CHOSEN = EngineTarget(EngineId("pi"), EngineBindingId("key"), ModelId("haiku"))
