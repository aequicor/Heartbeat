package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningMachineKey
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/**
 * Heartbeat learned skills of the self-learning registry. The same project predicate as the session prompt
 * applies: a skill learned in a project serves only its sessions, one learned without a project only chats
 * without a project. Reading the registry never starts it; until it reports Ready there are no skills.
 */
@Inject
internal class LearnedSkillsSource(private val machines: MachineRegistry) {
    /** Enabled skills visible in [workspace]; null is a chat without a project. */
    internal fun skills(workspace: WorkspaceRef?): List<LearnedInstruction> {
        val state = machines.find(AgentLearningMachineKey)?.state?.value as? AgentLearningState.Ready
            ?: return emptyList()
        return state.instructions.filter { it.isEnabled && it.kind == InstructionKind.Skill && it.project == workspace }
    }
}
