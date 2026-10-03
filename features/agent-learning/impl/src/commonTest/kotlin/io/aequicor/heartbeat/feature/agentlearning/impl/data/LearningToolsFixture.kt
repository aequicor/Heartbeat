package io.aequicor.heartbeat.feature.agentlearning.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEffect
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningMachineSpec
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningOutput
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.LearningMachine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

internal val PROJECT = WorkspaceRef("project")
internal val CHECKOUT = WorkspaceRef("checkout")
internal val TARGET = EngineTarget(EngineId("claude"), EngineBindingId("binding"), ModelId("sonnet"))

/**
 * Runs the real registry spec without a runtime; effects are recorded, outputs are emitted. Saves succeed unless
 * [isWritable] is false, then they fail the way the effect handler reports it.
 */
internal class SpecMachine(
    initial: AgentLearningState = AgentLearningState.Ready(),
    private val isWritable: Boolean = true,
) : Machine<AgentLearningState, AgentLearningIntent, AgentLearningOutput> {
    override val name: String = AgentLearningMachineSpec.name
    override val state = MutableStateFlow(initial)
    private val emitted = MutableSharedFlow<AgentLearningOutput>(extraBufferCapacity = 16)
    override val outputs: Flow<AgentLearningOutput> = emitted
    val effects = mutableListOf<AgentLearningEffect>()

    override suspend fun send(intent: AgentLearningIntent): SendResult {
        val resolution = AgentLearningMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        effects += resolution.effects
        resolution.outputs.forEach { emitted.emit(it) }
        // Report saves the way the effect handler does: confirm learned instructions or map the failure.
        for (persist in resolution.effects.filterIsInstance<AgentLearningEffect.Persist>()) {
            val result = if (isWritable) {
                persist.receipt?.let(AgentLearningIntent.Internal::Saved)
            } else {
                AgentLearningMachineSpec.onEffectFailure(persist, IllegalStateException("not writable"))
            }
            result?.let { send(it) }
        }
        return SendResult.Accepted
    }
}

internal class Toggles(var isEnabled: Boolean = true) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // The fake answers only the learning flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)

    @Suppress("UNCHECKED_CAST") // The fake answers only the learning flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
        check(toggle == AgentLearningEnabled)
        return isEnabled as T
    }
}

internal class Worktrees(tasks: Map<String, WorktreeTask> = emptyMap()) : MachineRegistry {
    private val worktree = object : MachineRef<WorktreeState, Nothing, WorktreeOutput> {
        override val name = WorktreeMachineKey.name
        override val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready(tasks))
        override val outputs: Flow<WorktreeOutput> = emptyFlow()
        override suspend fun send(intent: Nothing): SendResult = SendResult.Ignored
    }

    @Suppress("UNCHECKED_CAST") // The fake resolves only the worktree key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == WorktreeMachineKey) worktree as MachineRef<S, P, O> else null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = SendResult.NotRunning
}

internal class SavedProjects(private val projects: List<WorkspaceRef> = listOf(PROJECT)) : LocalWorkspaces {
    override val isAvailable: Boolean = true
    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(projects.map { LocalWorkspace(it, it.value) })
    override suspend fun register(directory: String): LocalWorkspace = error("unused")
    override suspend fun resolve(ref: WorkspaceRef): String? = null
}

/** Takes every intent with [result] and never reports an outcome, like a registry stuck in a save. */
internal class SilentMachine(private val result: SendResult = SendResult.Accepted) :
    Machine<AgentLearningState, AgentLearningIntent, AgentLearningOutput> {
    override val name: String = AgentLearningMachineSpec.name
    override val state = MutableStateFlow<AgentLearningState>(AgentLearningState.Ready())
    override val outputs: Flow<AgentLearningOutput> = MutableSharedFlow()
    override suspend fun send(intent: AgentLearningIntent): SendResult = result
}

internal fun tools(
    machine: LearningMachine = SpecMachine(),
    toggles: Toggles = Toggles(),
    worktrees: Worktrees = Worktrees(),
) = LearningAgentTools(
    machine,
    toggles,
    worktrees,
    SavedProjects(),
    object : PlatformInfo {
        override val host: HostPlatform = HostPlatform.Windows
    },
)

internal fun context(workspace: WorkspaceRef? = PROJECT, target: EngineTarget? = TARGET) = AgentToolContext(
    SessionRef(EngineId("claude"), SessionSourceId("local"), "native"),
    workspace,
    TurnId("turn"),
    target = target,
)
