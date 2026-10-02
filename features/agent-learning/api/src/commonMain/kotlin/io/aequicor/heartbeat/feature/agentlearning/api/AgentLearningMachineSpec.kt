package io.aequicor.heartbeat.feature.agentlearning.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Holds the profile's learned instructions and persists every change. The machine never runs tools or decides
 * approvals: the hosted tool asks the user first and sends only accepted instructions.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready(instructions, approval) | |
 * | Loading | LoadFailed | | Failed | StorageFailed |
 * | Failed | Reload | | Loading | Load |
 * | Idle / Loading / Failed | Learn | | same | Rejected(Unavailable) |
 * | Ready | Learn | same lesson exists | Ready | Rejected(Duplicate) |
 * | Ready | Learn | registry full | Ready | Rejected(Limit) |
 * | Ready | Learn | otherwise | Ready(+instruction, revision + 1) | Persist(receipt) |
 * | Ready | Saved | | Ready | Learned(receipt) |
 * | Ready | SetEnabled | known id, value differs | Ready(updated, revision + 1) | Persist |
 * | Ready | Edit | known id, texts differ and fit limits | Ready(updated, revision + 1) | Persist |
 * | Ready | Delete | known id | Ready(without it, revision + 1) | Persist |
 * | Ready | SetApproval | level differs | Ready(approval, approvalRevision + 1) | PersistApproval |
 * | Ready | SaveFailed | | Ready | StorageFailed, Rejected(NotPersisted) for a receipt |
 *
 * Effect failures: Load → LoadFailed, Persist → SaveFailed(receipt), PersistApproval → SaveFailed. A learned
 * instruction is confirmed only once written. After a failed load nothing is written, so a broken read never
 * replaces the stored registry with an empty one.
 */
public val AgentLearningMachineSpec: LearningSpec =
    machineSpec(AgentLearningMachineKey, AgentLearningState.Idle) {
        state<AgentLearningState.Idle> {
            on<AgentLearningIntent.Public.Start> {
                goto<AgentLearningState.Loading> { AgentLearningState.Loading }
                effect { AgentLearningEffect.Load }
            }
            rejectUnavailable()
        }
        state<AgentLearningState.Loading> {
            on<AgentLearningIntent.Internal.Loaded> {
                goto<AgentLearningState.Ready> {
                    AgentLearningState.Ready(intent.instructions.distinctBy { it.id }, intent.approval)
                }
            }
            on<AgentLearningIntent.Internal.LoadFailed> {
                goto<AgentLearningState.Failed> { AgentLearningState.Failed }
                output { AgentLearningOutput.StorageFailed }
            }
            rejectUnavailable()
        }
        state<AgentLearningState.Failed> {
            on<AgentLearningIntent.Public.Reload> {
                goto<AgentLearningState.Loading> { AgentLearningState.Loading }
                effect { AgentLearningEffect.Load }
            }
            rejectUnavailable()
        }
        state<AgentLearningState.Ready> {
            on<AgentLearningIntent.Public.Learn> {
                stay { state.learned(intent.instruction) }
                effect {
                    val receipt = LearnReceipt(intent.requestId, intent.instruction.id)
                    state.learned(intent.instruction).takeIf { it != state }?.persist(receipt)
                }
                output {
                    state.rejection(intent.instruction)?.let { AgentLearningOutput.Rejected(intent.requestId, it) }
                }
            }
            on<AgentLearningIntent.Internal.Saved> {
                output { AgentLearningOutput.Learned(intent.receipt.requestId, intent.receipt.id) }
            }
            on<AgentLearningIntent.Public.SetEnabled>(
                guard = { state.find(intent.id)?.let { it.isEnabled != intent.isEnabled } == true },
            ) {
                stay { state.changed(intent.id) { it.copy(isEnabled = intent.isEnabled) } }
                effect { state.changed(intent.id) { it.copy(isEnabled = intent.isEnabled) }.persist() }
            }
            on<AgentLearningIntent.Public.Edit>(guard = { state.edited(intent) != null }) {
                stay { checkNotNull(state.edited(intent)) }
                effect { checkNotNull(state.edited(intent)).persist() }
            }
            on<AgentLearningIntent.Public.Delete>(guard = { state.find(intent.id) != null }) {
                stay { state.removed(intent.id) }
                effect { state.removed(intent.id).persist() }
            }
            on<AgentLearningIntent.Public.SetApproval>(guard = { state.approval != intent.approval }) {
                stay { state.copy(approval = intent.approval, approvalRevision = state.approvalRevision + 1) }
                effect { AgentLearningEffect.PersistApproval(intent.approval, state.approvalRevision + 1) }
            }
            on<AgentLearningIntent.Internal.SaveFailed> {
                output { AgentLearningOutput.StorageFailed }
                output {
                    intent.receipt?.let { AgentLearningOutput.Rejected(it.requestId, LearnRejection.NotPersisted) }
                }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                AgentLearningEffect.Load -> AgentLearningIntent.Internal.LoadFailed
                is AgentLearningEffect.Persist -> AgentLearningIntent.Internal.SaveFailed(effect.receipt)
                is AgentLearningEffect.PersistApproval -> AgentLearningIntent.Internal.SaveFailed()
            }
        }
    }

/** Machine spec type of [AgentLearningMachineSpec]. */
public typealias LearningSpec = MachineSpec<
    AgentLearningState,
    AgentLearningIntent,
    AgentLearningEffect,
    AgentLearningOutput,
>

/** Before the registry is known, an instruction is refused instead of being lost on the next load. */
private fun <T : AgentLearningState> LearningStateBuilder<T>.rejectUnavailable() {
    on<AgentLearningIntent.Public.Learn> {
        output { AgentLearningOutput.Rejected(intent.requestId, LearnRejection.Unavailable) }
    }
}

private typealias LearningStateBuilder<T> =
    StateBuilder<AgentLearningState, T, AgentLearningIntent, AgentLearningEffect, AgentLearningOutput>

private fun AgentLearningState.Ready.find(id: InstructionId): LearnedInstruction? =
    instructions.firstOrNull { it.id == id }

private fun AgentLearningState.Ready.rejection(instruction: LearnedInstruction): LearnRejection? = when {
    instructions.any { it.isSameLesson(instruction) || it.id == instruction.id } -> LearnRejection.Duplicate
    instructions.size >= LearningLimits.INSTRUCTIONS -> LearnRejection.Limit
    else -> null
}

/** The state with [instruction] added, or unchanged when the registry rejects it. */
private fun AgentLearningState.Ready.learned(instruction: LearnedInstruction): AgentLearningState.Ready {
    if (rejection(instruction) != null) return this
    return copy(instructions = instructions + instruction, revision = revision + 1)
}

private fun AgentLearningState.Ready.changed(
    id: InstructionId,
    update: (LearnedInstruction) -> LearnedInstruction,
): AgentLearningState.Ready =
    copy(instructions = instructions.map { if (it.id == id) update(it) else it }, revision = revision + 1)

private fun AgentLearningState.Ready.removed(id: InstructionId): AgentLearningState.Ready =
    copy(instructions = instructions.filterNot { it.id == id }, revision = revision + 1)

/** The state after [edit], or null when it changes nothing, empties a text or exceeds the limits. */
private fun AgentLearningState.Ready.edited(edit: AgentLearningIntent.Public.Edit): AgentLearningState.Ready? {
    val current = find(edit.id) ?: return null
    val title = edit.title.trim()
    val content = edit.content.trim()
    if (title.isEmpty() || content.isEmpty()) return null
    val updated = current.copy(
        title = title,
        description = edit.description.trim(),
        content = content,
        updatedAtMillis = edit.atMillis,
    )
    val isUnchanged = updated.copy(updatedAtMillis = current.updatedAtMillis) == current
    val isTaken = instructions.any { it.id != edit.id && it.isSameLesson(updated) }
    return if (isUnchanged || isTaken || !updated.isWithinLimits()) null else changed(edit.id) { updated }
}

private fun AgentLearningState.Ready.persist(receipt: LearnReceipt? = null): AgentLearningEffect.Persist =
    AgentLearningEffect.Persist(instructions, revision, receipt)
