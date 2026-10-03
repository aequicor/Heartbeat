package io.aequicor.heartbeat.feature.agentlearning.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEffect
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentLearningEffectsTest {
    private val lesson = LearnedInstruction(InstructionId("1"), InstructionKind.General, null, "UTF-8", "Use UTF-8")

    @Test
    fun `saved registry is loaded back`() = runTest {
        val storage = MemoryLearning()
        val machine = RecordingScope()
        val effects = AgentLearningEffects(storage)

        effects.handle(AgentLearningEffect.Persist(listOf(lesson), revision = 1), machine)
        effects.handle(AgentLearningEffect.PersistApproval(LearningApproval.Ask), machine)
        effects.handle(AgentLearningEffect.Load, machine)

        assertEquals(
            listOf<AgentLearningIntent>(AgentLearningIntent.Internal.Loaded(listOf(lesson), LearningApproval.Ask)),
            machine.sent,
        )
    }

    @Test
    fun `a save older than the stored revision is dropped`() = runTest {
        val storage = MemoryLearning()
        val effects = AgentLearningEffects(storage)

        effects.handle(AgentLearningEffect.Persist(listOf(lesson), revision = 2), RecordingScope())
        effects.handle(AgentLearningEffect.Persist(emptyList(), revision = 1), RecordingScope())

        assertEquals(listOf(lesson), storage.load().instructions)
    }

    private class MemoryLearning : LearningStorage {
        private var stored = StoredLearning()
        override suspend fun load(): StoredLearning = stored
        override suspend fun saveInstructions(instructions: List<LearnedInstruction>) {
            stored = stored.copy(instructions = instructions)
        }
        override suspend fun saveApproval(approval: LearningApproval) {
            stored = stored.copy(approval = approval)
        }
    }

    private class RecordingScope : EffectScope<AgentLearningIntent> {
        val sent = mutableListOf<AgentLearningIntent>()
        override suspend fun send(intent: AgentLearningIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
