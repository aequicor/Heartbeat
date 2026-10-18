package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.data.InMemoryStudioRepository
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineStudioPlanningTest {
    private val planning = RecordingPlanning()
    private val sent = mutableListOf<AiStudioIntent>()
    private val machine = object : EffectScope<AiStudioIntent> {
        override suspend fun send(intent: AiStudioIntent): SendResult {
            sent += intent
            return SendResult.Ignored
        }
    }

    @Test
    fun `a planning chat enters planning mode before its first run`() = runTest {
        val effects = effects(InMemoryStudioRepository(TestClock(this)))
        effects.handle(
            AiStudioEffect.CreateSession(1, null, "Plan it", DefaultRunSettings, 7, isPlanning = true),
            machine,
        )
        val created = assertIs<AiStudioIntent.Internal.SessionCreated>(sent.single())
        assertEquals(listOf(created.sessionId), planning.entered)
    }

    @Test
    fun `an ordinary chat leaves planning alone`() = runTest {
        val effects = effects(InMemoryStudioRepository(TestClock(this)))
        effects.handle(AiStudioEffect.CreateSession(1, null, "Do it", DefaultRunSettings, 7), machine)
        assertTrue(planning.entered.isEmpty())
    }

    @Test
    fun `creation fails when planning cannot start and keeps the chat as an idle entry`() = runTest {
        val repository = InMemoryStudioRepository(TestClock(this))
        planning.canEnter = false
        val before = repository.observeWorkspace().first().sessions.size
        val request = AiStudioEffect.CreateSession(1, null, "Plan it", DefaultRunSettings, 7, isPlanning = true)
        assertFailsWith<IllegalStateException> { effects(repository).handle(request, machine) }
        assertTrue(sent.isEmpty())
        assertEquals(before + 1, repository.observeWorkspace().first().sessions.size)
    }

    private fun effects(repository: InMemoryStudioRepository) = EngineStudioEffects(
        repository,
        PlanningRuntime(),
        object : StudioAvailability {
            override suspend fun isEnabled() = true
            override fun observe() = flowOf(true)
        },
        planning = planning,
    )

    private class RecordingPlanning : StudioPlanning {
        var canEnter = true
        val entered = mutableListOf<String>()
        override val availability: Flow<Boolean> = flowOf(true)
        override val plans: Flow<Map<String, Plan>> = flowOf(emptyMap())

        override suspend fun enter(chatId: String): Boolean {
            if (canEnter) entered += chatId
            return canEnter
        }

        override suspend fun leave(chatId: String): Boolean = false

        override suspend fun bind(chatId: String, session: SessionRef) = Unit

        override suspend fun startImplementation(chatId: String, revision: Long) = false
    }
}

private class PlanningRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    override suspend fun defaults() = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings) =
        error("Creation must not start a run")

    override suspend fun cancel(sessionId: String) = error("unused")
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = error("unused")
}
