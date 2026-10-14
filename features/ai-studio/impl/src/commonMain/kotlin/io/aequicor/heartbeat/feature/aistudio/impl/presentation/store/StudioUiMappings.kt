package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ContextNoticeKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.LearningAction
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModels
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EffortUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.FailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.LearningCallUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.LearningKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

internal fun StudioSessionConfiguration.toUi(): SessionConfigurationUi = SessionConfigurationUi(
    applied.modelId,
    applied.reasoningEffort,
    applied.approval.toUi(),
    pendingOperation,
)

/** Models offered by the composer, from the most capable to the fastest. */
val StudioModelOptions: ImmutableList<ModelUi> = StudioModels.map {
    it.toUi()
}.toImmutableList()

internal fun StudioModel.toUi(): ModelUi = ModelUi(
    id,
    name,
    isResearchSupported,
    isLocalProjectSupported,
    shortName,
    reasoningEfforts.toImmutableList(),
    defaultReasoningEffort,
    studioModelTarget(id)?.takeIf { isModelSwitchSupported }?.let { "${it.engine.value}/${it.binding.value}" },
    isTrustSupported,
    inputSupport.toUi(),
    isScripted = StudioModels.any { it.id == id },
)

internal fun StudioPane.toUi(): PaneUi = PaneUi(id, sessionId, projectId, isCreating, isWorktree, isOrganism)
internal fun StudioEnvironment.toUi(): EnvironmentUi = when (this) {
    StudioEnvironment.Local -> EnvironmentUi.Local
    StudioEnvironment.Cloud -> EnvironmentUi.Cloud
}

internal fun RunSettings.toUi(): SettingsUi = SettingsUi(
    modelId,
    effort.toUi(),
    approval.toUi(),
)

internal fun ReasoningEffort.toUi(): EffortUi = when (this) {
    ReasoningEffort.Low -> EffortUi.Low
    ReasoningEffort.Medium -> EffortUi.Medium
    ReasoningEffort.High -> EffortUi.High
    ReasoningEffort.VeryHigh -> EffortUi.VeryHigh
}

internal fun EffortUi.toDomain(): ReasoningEffort = when (this) {
    EffortUi.Low -> ReasoningEffort.Low
    EffortUi.Medium -> ReasoningEffort.Medium
    EffortUi.High -> ReasoningEffort.High
    EffortUi.VeryHigh -> ReasoningEffort.VeryHigh
}

internal fun ApprovalMode.toUi(): ApprovalUi = when (this) {
    ApprovalMode.Ask -> ApprovalUi.Ask
    ApprovalMode.AutoEdits -> ApprovalUi.AutoEdits
    ApprovalMode.AutoApprove -> ApprovalUi.AutoApprove
}

internal fun ApprovalUi.toDomain(): ApprovalMode = when (this) {
    ApprovalUi.Ask -> ApprovalMode.Ask
    ApprovalUi.AutoEdits -> ApprovalMode.AutoEdits
    ApprovalUi.AutoApprove -> ApprovalMode.AutoApprove
}

internal fun StudioMessage.toUi(): MessageUi = when (this) {
    is StudioMessage.Prompt -> MessageUi.Prompt(
        id,
        createdAt,
        text,
        isTimestampKnown,
        attachments.filter { it.id.startsWith("attachment:") }.map {
            AttachmentUi(it.id.removePrefix("attachment:"), "", it.mediaType, 0)
        }.toImmutableList(),
    )

    is StudioMessage.Reply -> MessageUi.Reply(
        id,
        createdAt,
        text,
        tools.map { it.toUi() }.toImmutableList(),
        isStreaming,
        parts.map { part ->
            when (part) {
                is StudioReplyPart.Text -> ReplyPartUi.Text(part.id, part.text)
                is StudioReplyPart.Reasoning -> ReplyPartUi.Reasoning(part.id, part.text)
                is StudioReplyPart.Tool -> ReplyPartUi.Tool(part.tool.toUi())
            }
        }.toImmutableList(),
        isTimestampKnown,
        checklistIds.toImmutableList(),
        tools.mapNotNull { it.shownCardId }.distinct().toImmutableList(),
        tools.asSequence().filter { it.isLiveCard }.mapNotNull { it.shownCardId }.distinct().toImmutableList(),
    )

    is StudioMessage.Stopped -> MessageUi.Stopped(id, createdAt, elapsed)

    is StudioMessage.Failed -> MessageUi.Failed(id, createdAt, FailureUi.valueOf(kind.name))

    is StudioMessage.ContextNotice -> MessageUi.ContextNotice(id, createdAt, kind == ContextNoticeKind.RolledOver)
}

private fun StudioToolRun.toUi(): ToolUi = ToolUi(
    id = id,
    title = title,
    status = when (status) {
        ToolRunStatus.Pending -> ToolStatusUi.Pending
        ToolRunStatus.Cancelled -> ToolStatusUi.Cancelled
        ToolRunStatus.Running -> ToolStatusUi.Running
        ToolRunStatus.Done -> ToolStatusUi.Done
        ToolRunStatus.Failed -> ToolStatusUi.Failed
    },
    output = output,
    diff = diff,
    feedback = feedback?.toUi(),
    learning = learning?.let { call ->
        LearningCallUi(
            isSkillLoad = call.action == LearningAction.LoadSkill,
            kind = when (call.kind) {
                InstructionKind.General -> LearningKindUi.General
                InstructionKind.Model -> LearningKindUi.Model
                InstructionKind.Skill -> LearningKindUi.Skill
                null -> null
            },
            title = call.title,
            content = call.content,
        )
    },
)

internal fun StudioHarness.toUi(): HarnessOptionUi = HarnessOptionUi(
    id,
    title,
    description,
    projectIds.toImmutableSet(),
)
