package io.aequicor.heartbeat.feature.researchchat.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Independent question conversations, with durable profile observations and transient screen selection.
 *
 * | From | Intent / guard | To | Effect / output |
 * |---|---|---|---|
 * | Idle | Start | Loading | Load |
 * | Loading | Loaded, eligible | Ready | Observe |
 * | Loading | Loaded, ineligible | Disabled | — |
 * | Loading | LoadFailed | Failed | — |
 * | Failed | Retry | Loading | Load |
 * | Ready | Observed | Ready | — |
 * | Ready | SelectSession/Question, exists | Ready | — |
 * | Ready | NewSession/Question, enabled and no mutation | Ready, mutating | Create |
 * | Ready | AddAttachments/Add/Select/Share/RemoveResource, idle and enabled | Ready, mutating | source mutation |
 * | Ready | Created/Mutated/ResourceAdded/MutationFailed | Ready | import acknowledgement on success |
 * | Ready | Submit, text or selected sources, idle question and enabled | Ready, submitting | Run |
 * | Ready | Submitted | Ready | Submitted |
 * | Ready | RunFinished | Ready | — |
 * | Ready | Stop, native question running | Ready | Stop |
 *
 * Unknown selections, overlapping source mutations and duplicate submissions are ignored. Turning the
 * flag off leaves saved research readable and allows explicit stop, but blocks all new work. Profile
 * execution is never cancelled by selection changes or by leaving the screen. A turn explicitly stopped by the
 * user settles without a generic error; unsolicited interruptions and native failures remain visible.
 *
 * ```mermaid
 * stateDiagram-v2
 *     Idle --> Loading: Start
 *     Loading --> Ready: Loaded / eligible
 *     Loading --> Disabled: Loaded / disabled
 *     Loading --> Failed: LoadFailed
 *     Failed --> Loading: Retry
 *     Ready --> Ready: Select / Mutate / Submit / Observe
 * ```
 */
public val ResearchChatMachineSpec: MachineSpec<
    ResearchChatState,
    ResearchChatIntent,
    ResearchChatEffect,
    ResearchChatOutput,
> = machineSpec(ResearchChatMachineKey, ResearchChatState.Idle) {
    state<ResearchChatState.Idle> {
        on<ResearchChatIntent.Public.Start> {
            goto<ResearchChatState.Loading> { ResearchChatState.Loading(intent.target) }
            effect { ResearchChatEffect.Load(intent.target) }
        }
    }
    state<ResearchChatState.Loading> {
        on<ResearchChatIntent.Internal.Loaded>(guard = { intent.isEnabled }) {
            goto<ResearchChatState.Ready> {
                val session = intent.workspace.sessions.firstOrNull { it.target == state.target }
                    ?: intent.workspace.sessions.firstOrNull()
                ResearchChatState.Ready(
                    state.target,
                    intent.workspace,
                    session?.id,
                    session?.questions?.firstOrNull()?.id,
                )
            }
            effect { ResearchChatEffect.Observe }
        }
        on<ResearchChatIntent.Internal.Loaded>(guard = { !intent.isEnabled }) {
            goto<ResearchChatState.Disabled> { ResearchChatState.Disabled }
        }
        on<ResearchChatIntent.Internal.LoadFailed> {
            goto<ResearchChatState.Failed> { ResearchChatState.Failed(state.target) }
        }
    }
    state<ResearchChatState.Disabled>()
    state<ResearchChatState.Failed> {
        on<ResearchChatIntent.Public.Retry> {
            goto<ResearchChatState.Loading> { ResearchChatState.Loading(state.target) }
            effect { ResearchChatEffect.Load(state.target) }
        }
    }
    state<ResearchChatState.Ready> {
        selection()
        resources()
        execution()
        on<ResearchChatIntent.Internal.Observed> {
            stay { state.copy(workspace = intent.workspace, isEnabled = intent.isEnabled) }
        }
        on<ResearchChatIntent.Internal.Created> {
            stay {
                state.copy(
                    workspace = intent.workspace,
                    sessionId = intent.sessionId,
                    questionId = intent.questionId,
                    isMutating = false,
                )
            }
        }
        on<ResearchChatIntent.Internal.Mutated> { stay { state.copy(isMutating = false) } }
        on<ResearchChatIntent.Internal.ResourceAdded> {
            stay { state.copy(isMutating = false) }
            output { ResearchChatOutput.ResourceAdded }
        }
        on<ResearchChatIntent.Internal.MutationFailed> {
            stay { state.copy(isMutating = false, hasError = true) }
        }
        on<ResearchChatIntent.Public.DismissError> { stay { state.copy(hasError = false) } }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            is ResearchChatEffect.Load -> ResearchChatIntent.Internal.LoadFailed

            is ResearchChatEffect.Run -> ResearchChatIntent.Internal.RunFinished(effect.questionId, true)

            ResearchChatEffect.Observe, is ResearchChatEffect.CreateSession, is ResearchChatEffect.CreateQuestion,
            is ResearchChatEffect.AddResource, is ResearchChatEffect.AddAttachments,
            is ResearchChatEffect.SelectResource,
            is ResearchChatEffect.ShareResource,
            is ResearchChatEffect.RemoveResource, is ResearchChatEffect.Stop,
            -> ResearchChatIntent.Internal.MutationFailed
        }
    }
}

private typealias ResearchTransitions = StateBuilder<
    ResearchChatState,
    ResearchChatState.Ready,
    ResearchChatIntent,
    ResearchChatEffect,
    ResearchChatOutput,
>

private fun ResearchTransitions.selection() {
    on<ResearchChatIntent.Public.NewSession>(guard = { state.canMutate() }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect { ResearchChatEffect.CreateSession(state.target) }
    }
    on<ResearchChatIntent.Public.SelectSession>(guard = { state.workspace.sessions.any { it.id == intent.id } }) {
        stay {
            state.copy(
                sessionId = intent.id,
                questionId = state.workspace.sessions.first { it.id == intent.id }.questions.firstOrNull()?.id,
            )
        }
    }
    on<ResearchChatIntent.Public.NewQuestion>(guard = { state.canMutate() && state.session != null }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect { ResearchChatEffect.CreateQuestion(requireNotNull(state.sessionId)) }
    }
    on<ResearchChatIntent.Public.SelectQuestion>(guard = {
        state.session?.questions?.any { it.id == intent.id } == true
    }) {
        stay { state.copy(questionId = intent.id) }
    }
}

private fun ResearchTransitions.resources() {
    on<ResearchChatIntent.Public.AddAttachments>(guard = {
        state.canMutate() && intent.attachments.isNotEmpty() &&
            intent.questionId !in state.workspace.running && intent.questionId !in state.submitting &&
            state.workspace.sessions.any { session ->
                session.id == intent.sessionId && session.questions.any { it.id == intent.questionId }
            }
    }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect { ResearchChatEffect.AddAttachments(intent) }
    }
    on<ResearchChatIntent.Public.AddResource>(guard = { state.canEditSources() && intent.value.isNotBlank() }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect {
            ResearchChatEffect.AddResource(requireNotNull(state.sessionId), requireNotNull(state.questionId), intent)
        }
    }
    on<ResearchChatIntent.Public.SetResourceSelected>(guard = {
        state.canEditSources() && state.hasResource(intent.resourceId)
    }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect {
            ResearchChatEffect.SelectResource(
                requireNotNull(state.sessionId),
                requireNotNull(state.questionId),
                intent.resourceId,
                intent.isSelected,
            )
        }
    }
    on<ResearchChatIntent.Public.ShareResource>(guard = {
        state.canEditSources() && state.hasResource(intent.resourceId)
    }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect { ResearchChatEffect.ShareResource(requireNotNull(state.sessionId), intent.resourceId) }
    }
    on<ResearchChatIntent.Public.RemoveResource>(guard = {
        state.canEditSources() && state.hasResource(intent.resourceId)
    }) {
        stay { state.copy(isMutating = true, hasError = false) }
        effect { ResearchChatEffect.RemoveResource(requireNotNull(state.sessionId), intent.resourceId) }
    }
}

private fun ResearchTransitions.execution() {
    on<ResearchChatIntent.Public.Submit>(guard = {
        state.canEditSources() && (
            intent.prompt.isNotBlank() ||
                state.session?.selectedResources(requireNotNull(state.question))?.isNotEmpty() == true
        )
    }) {
        stay { state.copy(submitting = state.submitting + requireNotNull(state.questionId), hasError = false) }
        effect {
            ResearchChatEffect.Run(
                requireNotNull(state.sessionId),
                requireNotNull(state.questionId),
                intent.prompt.trim(),
            )
        }
    }
    on<ResearchChatIntent.Internal.Submitted> {
        output { ResearchChatOutput.Submitted(intent.questionId) }
    }
    on<ResearchChatIntent.Internal.RunFinished> {
        stay {
            state.copy(submitting = state.submitting - intent.questionId, hasError = state.hasError || intent.isFailed)
        }
    }
    on<ResearchChatIntent.Public.Stop>(guard = { state.questionId in state.workspace.running }) {
        effect { ResearchChatEffect.Stop(requireNotNull(state.questionId)) }
    }
}

private fun ResearchChatState.Ready.canMutate(): Boolean = isEnabled && !isMutating
private fun ResearchChatState.Ready.canEditSources(): Boolean = canMutate() && question != null && !isRunning
private fun ResearchChatState.Ready.hasResource(id: String): Boolean = session?.resources?.any { it.id == id } == true
