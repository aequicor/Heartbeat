package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration
import kotlin.time.Instant

/** A workspace column as the screen shows it. */
@Immutable
data class PaneUi(
    val id: Int,
    val sessionId: String? = null,
    val projectId: String? = null,
    val isCreating: Boolean = false,
    val isWorktree: Boolean = false,
    val isOrganism: Boolean = false,
)

/** Where a project's agent runs. */
enum class EnvironmentUi { Local, Cloud }

/** Reasoning budget choices of the composer. */
enum class EffortUi { Low, Medium, High, VeryHigh }

/** Approval choices of the composer. */
enum class ApprovalUi { Ask, AutoEdits, AutoApprove }

/**
 * A model offered by the composer. [connectionKey] names its engine connection: a running chat may switch only
 * between models sharing it; null for scripted demo models and engines that cannot switch a session's model.
 */
@Immutable
data class ModelUi(
    val id: String,
    val name: String,
    val isResearchSupported: Boolean = false,
    val isLocalProjectSupported: Boolean = false,
    val shortName: String = name,
    val reasoningEfforts: ImmutableList<String> = persistentListOf(),
    val defaultReasoningEffort: String? = null,
    val connectionKey: String? = null,
    val isTrustSupported: Boolean = false,
    val inputSupport: InputSupportUi = InputSupportUi(),
)

/** Composer preferences mirrored from the machine. */
@Immutable
data class SettingsUi(
    val modelId: String,
    val effort: EffortUi,
    val approval: ApprovalUi,
    val engineEfforts: ImmutableMap<String, String> = persistentMapOf(),
    val nativeEffort: String? = null,
) {
    /** The selected model, or the first one when the id is unknown. */
    val model: ModelUi get() = StudioModelOptions.firstOrNull { it.id == modelId } ?: StudioModelOptions.first()
}

/** Confirmed configuration of one native conversation; pending operations never replace the applied values. */
@Immutable
data class SessionConfigurationUi(
    val modelId: String,
    val reasoningEffort: String?,
    val approval: ApprovalUi,
    val pendingOperation: String? = null,
)

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
)

/** Progress of an agent tool call. */
enum class ToolStatusUi { Pending, Running, Done, Failed, Cancelled }

/** A tool call of a reply: literal console [output] and an optional unified [diff]. */
@Immutable
data class ToolUi(
    val id: String,
    val title: String,
    val status: ToolStatusUi,
    val output: String,
    val diff: String?,
    val feedback: FeedbackUi? = null,
    val learning: LearningCallUi? = null,
)

/** Self-learning tool call shown as a card: [kind] is general, model or skill when the agent named it. */
@Immutable
data class LearningCallUi(val isSkillLoad: Boolean, val kind: LearningKindUi?, val title: String, val content: String)

/** Kind of an instruction the agent remembers. */
enum class LearningKindUi { General, Model, Skill }

/** Chronological blocks inside one answer, retaining native identities across streaming updates. */
@Immutable
sealed interface ReplyPartUi {
    /** Stable identity within one rendered answer. */
    val id: String

    /** Readable prose positioned between agent tool calls. */
    data class Text(override val id: String, val text: String) : ReplyPartUi

    /** Engine-exposed reasoning presented as a subordinate disclosure. */
    data class Reasoning(override val id: String, val text: String) : ReplyPartUi

    /** One invocation with its current status and output. */
    data class Tool(val tool: ToolUi) : ReplyPartUi {
        override val id: String get() = tool.id
    }
}

/** Screen-level class of a failed run, mirroring the domain failure kind. */
enum class FailureUi { Limit, Context, Authentication, Network, Unknown }

/** One transcript entry as the screen shows it. */
@Immutable
sealed interface MessageUi {
    /** Stable id within the session. */
    val id: String

    /** When the entry was written. */
    val createdAt: Instant

    /** Whether [createdAt] came from the engine and may be shown to the user. */
    val isTimestampKnown: Boolean get() = true

    /** A prompt of the user. */
    data class Prompt(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        override val isTimestampKnown: Boolean = true,
        val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    ) : MessageUi

    /** An agent answer, streamed while [isStreaming]. */
    data class Reply(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        val tools: ImmutableList<ToolUi>,
        val isStreaming: Boolean,
        val parts: ImmutableList<ReplyPartUi> = persistentListOf(),
        override val isTimestampKnown: Boolean = true,
        val checklistIds: ImmutableList<String> = persistentListOf(),
    ) : MessageUi

    /** The user stopped the run after [elapsed]. */
    data class Stopped(override val id: String, override val createdAt: Instant, val elapsed: Duration) : MessageUi

    /** The run failed. */
    data class Failed(
        override val id: String,
        override val createdAt: Instant,
        val kind: FailureUi = FailureUi.Unknown,
    ) : MessageUi
}

internal val DefaultSettingsUi: SettingsUi = DefaultRunSettings.toUi()

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
    )

    is StudioMessage.Stopped -> MessageUi.Stopped(id, createdAt, elapsed)

    is StudioMessage.Failed -> MessageUi.Failed(id, createdAt, FailureUi.valueOf(kind.name))
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

/**
 * Harnesses of the "+" menu: enabled [options], committed connections by chat id and choices kept on the panes
 * of chats that have no session yet.
 */
@Immutable
data class HarnessChoicesUi(
    val options: ImmutableList<StudioHarness> = persistentListOf(),
    val chats: ImmutableMap<String, ImmutableSet<String>> = persistentMapOf(),
    val panes: ImmutableMap<Int, ImmutableSet<String>> = persistentMapOf(),
)

/** One harness of a pane's "+" menu; [isPermanent] ones are active by scope and cannot be toggled. */
@Immutable
data class HarnessChoiceUi(val id: String, val title: String, val isSelected: Boolean, val isPermanent: Boolean)
