package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineStudioEnvironmentTest {
    @Test
    fun `accepted turns keep their context while subsequent turns use the reconnect`() = runTest {
        fun environment(): StudioChatEnvironment = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable = AutoCloseable { }
        }
        val first = environment()
        val second = environment()
        val runtime = EnvironmentRuntime()
        val effects = EngineStudioEffects(
            InMemoryStudioRepository(TestClock(this)),
            runtime,
            object : StudioAvailability {
                override suspend fun isEnabled() = true
                override fun observe() = flowOf(true)
            },
        )
        val machine = object : EffectScope<AiStudioIntent> {
            override suspend fun send(intent: AiStudioIntent) = SendResult.Accepted
        }
        val effect = AiStudioEffect.Run("chat", "hello", DefaultRunSettings)
        val running = launch { effects.handle(effect.copy(environment = first), machine) }
        runCurrent()
        assertEquals(listOf<StudioChatEnvironment?>(first), runtime.accepted)
        runtime.complete.complete(Unit)
        running.join()
        effects.handle(effect.copy(environment = second), machine)
        effects.handle(effect, machine)
        assertEquals(listOf(first, second, null), runtime.accepted)
        assertEquals(runtime.accepted, runtime.finished)
    }
}

private class EnvironmentRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    val accepted = mutableListOf<StudioChatEnvironment?>()
    val finished = mutableListOf<StudioChatEnvironment?>()
    val complete = CompletableDeferred<Unit>()
    override suspend fun defaults() = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("unused")
    override suspend fun run(
        sessionId: String,
        prompt: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        submissionId: String,
        environment: StudioChatEnvironment?,
        onAccepted: suspend () -> Unit,
    ): RunOutcome {
        accepted += environment
        onAccepted()
        complete.await()
        finished += environment
        return RunOutcome.Completed
    }
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = Unit
}
