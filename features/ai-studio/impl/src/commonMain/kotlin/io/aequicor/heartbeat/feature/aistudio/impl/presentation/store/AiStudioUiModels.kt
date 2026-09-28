package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModels
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import kotlinx.collections.immutable.ImmutableList
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
)

/** Where a project's agent runs. */
enum class EnvironmentUi { Local, Cloud }

/** Reasoning budget choices of the composer. */
enum class EffortUi { Low, Medium, High, VeryHigh }

/** Approval choices of the composer. */
enum class ApprovalUi { Ask, AutoApprove }

/** A model offered by the composer. */
@Immutable
data class ModelUi(val id: String, val name: String, val isResearchSupported: Boolean = false)

/** Composer preferences mirrored from the machine. */
@Immutable
data class SettingsUi(val modelId: String, val effort: EffortUi, val approval: ApprovalUi) {
    /** The selected model, or the first one when the id is unknown. */
    val model: ModelUi get() = StudioModelOptions.firstOrNull { it.id == modelId } ?: StudioModelOptions.first()
}

/** Models offered by the composer, from the most capable to the fastest. */
val StudioModelOptions: ImmutableList<ModelUi> = StudioModels.map { ModelUi(it.id, it.name) }.toImmutableList()

/** Progress of an agent tool call. */
enum class ToolStatusUi { Running, Done, Failed }

/** A tool call of a reply: literal console [output] and an optional unified [diff]. */
@Immutable
data class ToolUi(val id: String, val title: String, val status: ToolStatusUi, val output: String, val diff: String?)

/** One transcript entry as the screen shows it. */
@Immutable
sealed interface MessageUi {
    /** Stable id within the session. */
    val id: String

    /** When the entry was written. */
    val createdAt: Instant

    /** A prompt of the user. */
    data class Prompt(override val id: String, override val createdAt: Instant, val text: String) : MessageUi

    /** An agent answer, streamed while [isStreaming]. */
    data class Reply(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        val tools: ImmutableList<ToolUi>,
        val isStreaming: Boolean,
    ) : MessageUi

    /** The user stopped the run after [elapsed]. */
    data class Stopped(override val id: String, override val createdAt: Instant, val elapsed: Duration) : MessageUi

    /** The run failed. */
    data class Failed(override val id: String, override val createdAt: Instant) : MessageUi
}

internal val DefaultSettingsUi: SettingsUi = DefaultRunSettings.toUi()

internal fun StudioPane.toUi(): PaneUi = PaneUi(id, sessionId, projectId, isCreating)

internal fun StudioEnvironment.toUi(): EnvironmentUi = when (this) {
    StudioEnvironment.Local -> EnvironmentUi.Local
    StudioEnvironment.Cloud -> EnvironmentUi.Cloud
}

internal fun RunSettings.toUi(): SettingsUi = SettingsUi(modelId, effort.toUi(), approval.toUi())

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
    ApprovalMode.AutoApprove -> ApprovalUi.AutoApprove
}

internal fun ApprovalUi.toDomain(): ApprovalMode = when (this) {
    ApprovalUi.Ask -> ApprovalMode.Ask
    ApprovalUi.AutoApprove -> ApprovalMode.AutoApprove
}

internal fun StudioMessage.toUi(): MessageUi = when (this) {
    is StudioMessage.Prompt -> MessageUi.Prompt(id, createdAt, text)

    is StudioMessage.Reply -> MessageUi.Reply(
        id,
        createdAt,
        text,
        tools.map { it.toUi() }.toImmutableList(),
        isStreaming,
    )

    is StudioMessage.Stopped -> MessageUi.Stopped(id, createdAt, elapsed)

    is StudioMessage.Failed -> MessageUi.Failed(id, createdAt)
}

private fun StudioToolRun.toUi(): ToolUi = ToolUi(
    id = id,
    title = title,
    status = when (status) {
        ToolRunStatus.Running -> ToolStatusUi.Running
        ToolRunStatus.Done -> ToolStatusUi.Done
        ToolRunStatus.Failed -> ToolStatusUi.Failed
    },
    output = output,
    diff = diff,
)
