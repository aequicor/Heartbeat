package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningMachineKey
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningOutput
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class LearnedSkillsSourceTest {
    private val project = WorkspaceRef("project")

    @Test
    fun `only enabled skills of the same project are visible`() {
        val source = LearnedSkillsSource(
            registry(
                instructions = listOf(
                    skill("1", "verify", project = project),
                    skill("2", "disabled", project = project, isEnabled = false),
                    skill("3", "other-project", project = WorkspaceRef("other")),
                    skill("4", "detached", project = null),
                    general("5", "a general instruction", project = project),
                ),
            ),
        )

        assertEquals(listOf("verify"), source.skills(project).map { it.title })
        assertEquals(listOf("detached"), source.skills(null).map { it.title })
    }

    @Test
    fun `a registry that is not ready or absent has no skills`() {
        assertEquals(emptyList(), LearnedSkillsSource(registry(instructions = emptyList())).skills(project))
        assertEquals(emptyList(), LearnedSkillsSource(emptyRegistry()).skills(project))
    }

    private fun skill(id: String, title: String, project: WorkspaceRef?, isEnabled: Boolean = true) =
        LearnedInstruction(
            id = InstructionId(id),
            kind = InstructionKind.Skill,
            project = project,
            title = title,
            content = "skill body",
            description = "when to use $title",
            isEnabled = isEnabled,
        )

    private fun general(id: String, title: String, project: WorkspaceRef?) = LearnedInstruction(
        id = InstructionId(id),
        kind = InstructionKind.General,
        project = project,
        title = title,
        content = "general body",
    )

    private fun registry(instructions: List<LearnedInstruction>): MachineRegistry =
        learningRegistry(MutableStateFlow<AgentLearningState>(AgentLearningState.Ready(instructions = instructions)))

    private fun emptyRegistry(): MachineRegistry = learningRegistry(null)
}

/** Minimal registry addressing one learning machine state; unused operations fail loud. */
internal fun learningRegistry(state: MutableStateFlow<AgentLearningState>?): MachineRegistry {
    val ref = state?.let(::LearningMachineRef)
    return object : MachineRegistry {
        @Suppress("UNCHECKED_CAST")
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = ref as? MachineRef<S, P, O>

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): MutableStateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = SendResult.NotRunning
    }
}

private class LearningMachineRef(state: MutableStateFlow<AgentLearningState>) :
    MachineRef<AgentLearningState, AgentLearningIntent.Public, AgentLearningOutput> {
    override val name = AgentLearningMachineKey.name
    override val state = state
    override val outputs: Flow<AgentLearningOutput> = emptyFlow()
    override suspend fun send(intent: AgentLearningIntent.Public): SendResult = SendResult.NotRunning
}
