package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineStudioEffectsUsageTest {
    @Test
    fun `closing the feature detaches usage after the machine stops and preserves profile runs`() = runTest {
        val runtime = UsageRuntime()
        val effects = EngineStudioEffects(
            InMemoryStudioRepository(TestClock(this)),
            runtime,
            object : StudioAvailability {
                override suspend fun isEnabled() = true
                override fun observe() = flowOf(true)
            },
        )
        var isMachineClosed = false
        val sent = mutableListOf<AiStudioIntent>()
        val machine = object : EffectScope<AiStudioIntent> {
            override suspend fun send(intent: AiStudioIntent): SendResult {
                check(!isMachineClosed) { "The machine has already stopped" }
                sent += intent
                return SendResult.Accepted
            }
        }
        effects.handle(AiStudioEffect.ObserveUsageTargets(setOf("model")), machine)
        val feature = Job(coroutineContext[Job])
        CoroutineScope(coroutineContext + feature).launch {
            effects.handle(AiStudioEffect.ObserveRuntime, machine)
        }
        runCurrent()
        assertEquals(setOf("model"), runtime.targets)
        assertEquals(1, runtime.state.subscriptionCount.value)

        isMachineClosed = true
        feature.cancelAndJoin()

        assertTrue(runtime.targets.isEmpty())
        assertEquals(0, runtime.state.subscriptionCount.value)
        assertEquals(setOf("running-chat"), runtime.state.value.running)
        assertEquals(listOf<AiStudioIntent>(AiStudioIntent.Internal.RuntimeChanged(runtime.state.value)), sent)
    }
}

private class UsageRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState(running = setOf("running-chat")))
    var targets = emptySet<String>()

    override suspend fun observeUsageTargets(modelIds: Set<String>) {
        currentCoroutineContext().ensureActive()
        targets = modelIds
    }

    override suspend fun defaults() = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings) = error("unused")
    override suspend fun cancel(sessionId: String) = error("Usage cleanup must preserve profile runs")
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = error("unused")
}
