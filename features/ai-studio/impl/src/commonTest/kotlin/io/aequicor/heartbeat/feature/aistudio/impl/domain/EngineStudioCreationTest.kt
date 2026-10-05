package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EngineStudioCreationTest {
    @Test
    fun `creation echoes its request token and leaves starting the native run to the machine`() = runTest {
        val repository = InMemoryStudioRepository(TestClock(this))
        val effects = EngineStudioEffects(
            repository,
            CreationRuntime(),
            object : StudioAvailability {
                override suspend fun isEnabled() = true
                override fun observe() = flowOf(true)
            },
        )
        val sent = mutableListOf<AiStudioIntent>()
        val machine = object : EffectScope<AiStudioIntent> {
            override suspend fun send(intent: AiStudioIntent): SendResult {
                sent += intent
                return SendResult.Ignored
            }
        }
        val request = AiStudioEffect.CreateSession(1, null, "First prompt", DefaultRunSettings, 42)
        effects.handle(request, machine)
        val created = assertIs<AiStudioIntent.Internal.SessionCreated>(sent.single())
        assertEquals(
            AiStudioIntent.Internal.SessionCreated(1, created.sessionId, "First prompt", DefaultRunSettings, 42),
            created,
        )
        val session = repository.observeWorkspace().first().session(created.sessionId)
        assertEquals("First prompt", session?.title)
        assertEquals(false, session?.isArchived)
        assertEquals(emptyList(), repository.observeMessages(created.sessionId).first())
    }

    @Test
    fun `an organism chat is created only once its organism could be conceived`() = runTest {
        val repository = InMemoryStudioRepository(TestClock(this))
        val effects = EngineStudioEffects(
            repository,
            CreationRuntime(),
            object : StudioAvailability {
                override suspend fun isEnabled() = true
                override fun observe() = flowOf(true)
            },
        )
        val sent = mutableListOf<AiStudioIntent>()
        val machine = object : EffectScope<AiStudioIntent> {
            override suspend fun send(intent: AiStudioIntent): SendResult {
                sent += intent
                return SendResult.Ignored
            }
        }
        val before = repository.observeWorkspace().first().sessions
        val request = AiStudioEffect.CreateSession(1, null, "Goal", DefaultRunSettings, 42, isOrganism = true)
        // The demo backend has no organisms: the refusal fails the creation before any chat exists.
        assertFailsWith<IllegalStateException> { effects.handle(request, machine) }
        assertEquals(emptyList(), sent)
        assertEquals(before, repository.observeWorkspace().first().sessions)
    }
}

private class CreationRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    override suspend fun defaults() = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings) =
        error("Creation must not start a run before its result is accepted")

    override suspend fun cancel(sessionId: String) = error("unused")
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = error("unused")
}
