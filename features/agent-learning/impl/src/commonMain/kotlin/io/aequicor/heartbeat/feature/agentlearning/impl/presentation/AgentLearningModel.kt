package io.aequicor.heartbeat.feature.agentlearning.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningOutput
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import io.aequicor.heartbeat.feature.agentlearning.api.LearningLimits
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.LearningMachine
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock

private typealias LearningPipeline =
    PipelineContext<AgentLearningScreenState, AgentLearningScreenIntent, AgentLearningScreenAction>

/**
 * Registry screen store. It mirrors the profile machine and the saved projects; every change goes to the machine,
 * which persists it, so the list survives the screen. The store keeps only the filter, the expanded row and an edit
 * or removal in progress.
 */
internal class AgentLearningModel(
    private val machine: LearningMachine,
    workspaces: LocalWorkspaces,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    /** The screen store, retained for the lifetime of this screen. */
    val store = factory.create<AgentLearningScreenState, AgentLearningScreenIntent, AgentLearningScreenAction>(
        "AgentLearning",
        AgentLearningScreenState().reflectRegistry(machine.state.value),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            // A failed load also reports StorageFailed; its state already shows the load error and the retry.
            val isReady = machine.state.value is AgentLearningState.Ready
            if (output == AgentLearningOutput.StorageFailed && isReady) {
                updateState { copy(error = LearningErrorUi.SaveFailed) }
            }
        }) { reflectRegistry(it) }
        whileSubscribed {
            workspaces.observe().collect { saved ->
                updateState { copy(projects = saved.map { ProjectUi(it.ref.value, it.name) }.toImmutableList()) }
            }
        }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun LearningPipeline.handle(intent: AgentLearningScreenIntent) {
        when (intent) {
            is AgentLearningScreenIntent.SelectApproval ->
                sendTo(machine, AgentLearningIntent.Public.SetApproval(intent.approval.toDomain()))

            is AgentLearningScreenIntent.SetEnabled ->
                sendTo(machine, AgentLearningIntent.Public.SetEnabled(InstructionId(intent.id), intent.isEnabled))

            is AgentLearningScreenIntent.SelectFilter -> updateState { copy(filter = intent.filter) }

            is AgentLearningScreenIntent.ToggleExpanded ->
                updateState { copy(expanded = intent.id.takeUnless { it == expanded }) }

            is AgentLearningScreenIntent.DraftIntent -> handleDraft(intent)

            is AgentLearningScreenIntent.Delete -> updateState { copy(deleting = intent.id) }

            AgentLearningScreenIntent.ConfirmDelete -> withState {
                val id = deleting
                updateState { copy(deleting = null, expanded = expanded.takeUnless { it == id }) }
                if (id != null) sendTo(machine, AgentLearningIntent.Public.Delete(InstructionId(id)))
            }

            AgentLearningScreenIntent.CancelDelete -> updateState { copy(deleting = null) }

            AgentLearningScreenIntent.Reload -> {
                // The banner gives way to the loading state; a new failure brings it back.
                updateState { copy(error = null) }
                sendTo(machine, AgentLearningIntent.Public.Reload)
            }

            AgentLearningScreenIntent.DismissError ->
                updateState { copy(error = error.takeIf { it == LearningErrorUi.LoadFailed }) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun LearningPipeline.handleDraft(intent: AgentLearningScreenIntent.DraftIntent) {
        when (intent) {
            is AgentLearningScreenIntent.Edit -> updateState {
                copy(draft = instructions.firstOrNull { it.id == intent.id }?.toDraft(), error = null)
            }

            is AgentLearningScreenIntent.ChangeDraft -> updateState {
                copy(
                    draft = draft?.copy(
                        title = intent.title,
                        description = intent.description,
                        content = intent.content,
                    ),
                    error = error.takeUnless { it == LearningErrorUi.EditRejected },
                )
            }

            AgentLearningScreenIntent.SaveDraft -> saveDraft()

            AgentLearningScreenIntent.CancelDraft ->
                updateState { copy(draft = null, error = error.takeUnless { it == LearningErrorUi.EditRejected }) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun LearningPipeline.saveDraft() = withState {
        val current = draft ?: return@withState
        val edit = AgentLearningIntent.Public.Edit(
            InstructionId(current.id),
            current.title,
            current.description,
            current.content,
            clock.now().toEpochMilliseconds(),
        )
        // The registry accepts an unchanged draft without a write, so saving it closes the editor like any save.
        val result = sendTo(machine, edit)
        updateState {
            if (result == SendResult.Accepted) copy(draft = null) else copy(error = LearningErrorUi.EditRejected)
        }
    }
}

/** Registry state mirrored into the screen; a vanished row loses its expansion, edit and pending removal. */
private fun AgentLearningScreenState.reflectRegistry(state: AgentLearningState): AgentLearningScreenState =
    when (state) {
        AgentLearningState.Idle, AgentLearningState.Loading -> copy(isLoaded = false)

        AgentLearningState.Failed -> copy(isLoaded = false, error = LearningErrorUi.LoadFailed)

        is AgentLearningState.Ready -> {
            val rows = state.instructions.map { it.toUi() }.toImmutableList()
            val ids = rows.map { it.id }.toSet()
            copy(
                isLoaded = true,
                approval = state.approval.toUi(),
                instructions = rows,
                expanded = expanded?.takeIf { it in ids },
                draft = draft?.takeIf { it.id in ids },
                deleting = deleting?.takeIf { it in ids },
                error = error.takeUnless { it == LearningErrorUi.LoadFailed },
            )
        }
    }

private fun LearnedInstruction.toUi() = InstructionUi(
    id = id.value,
    kind = when (kind) {
        InstructionKind.General -> KindUi.General
        InstructionKind.Model -> KindUi.Model
        InstructionKind.Skill -> KindUi.Skill
    },
    title = title,
    description = description,
    content = content,
    isEnabled = isEnabled,
    projectKey = project?.value,
    model = modelScope?.let { scope -> listOfNotNull(scope.engine.value, scope.model?.value).joinToString(" · ") },
)

private fun InstructionUi.toDraft() = DraftUi(
    id,
    kind,
    title,
    description,
    content,
    titleLimit = LearningLimits.TITLE,
    descriptionLimit = LearningLimits.DESCRIPTION,
    contentLimit = LearningLimits.content(kind.toDomain()),
)

private fun KindUi.toDomain(): InstructionKind = when (this) {
    KindUi.General -> InstructionKind.General
    KindUi.Model -> InstructionKind.Model
    KindUi.Skill -> InstructionKind.Skill
}

private fun LearningApproval.toUi(): ApprovalUi = when (this) {
    LearningApproval.Ask -> ApprovalUi.Ask
    LearningApproval.Automatic -> ApprovalUi.Automatic
    LearningApproval.AcceptAll -> ApprovalUi.AcceptAll
}

private fun ApprovalUi.toDomain(): LearningApproval = when (this) {
    ApprovalUi.Ask -> LearningApproval.Ask
    ApprovalUi.Automatic -> LearningApproval.Automatic
    ApprovalUi.AcceptAll -> LearningApproval.AcceptAll
}
