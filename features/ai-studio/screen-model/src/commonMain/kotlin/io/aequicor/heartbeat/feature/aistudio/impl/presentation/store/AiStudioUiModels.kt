package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
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
    /** A demo model of the scripted agent; its approval and effort preferences are local behavior. */
    val isScripted: Boolean = false,
)

/** Composer preferences mirrored from the machine. */
@Immutable
data class SettingsUi(
    val modelId: String,
    val effort: EffortUi,
    val approval: ApprovalUi,
    val engineEfforts: ImmutableMap<String, String> = persistentMapOf(),
    val nativeEffort: String? = null,
)

/** Confirmed configuration of one native conversation; pending operations never replace the applied values. */
@Immutable
data class SessionConfigurationUi(
    val modelId: String,
    val reasoningEffort: String?,
    val approval: ApprovalUi,
    val pendingOperation: String? = null,
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

    /** [count] tool calls of the reply shown as one line while the feed collapses agent work. */
    data class CollapsedTools(override val id: String, val count: Int, val isRunning: Boolean) : ReplyPartUi
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
    ) : MessageUi {
        /** How this instance looks in a collapsed feed, computed once per settings; not part of equality. */
        val collapseMemo: CollapseMemo = CollapseMemo()
    }

    /** The user stopped the run after [elapsed]. */
    data class Stopped(override val id: String, override val createdAt: Instant, val elapsed: Duration) : MessageUi

    /** A context divider: compacted, or continued in a new session. */
    data class ContextNotice(override val id: String, override val createdAt: Instant, val isRollover: Boolean) :
        MessageUi

    /** The run failed. */
    data class Failed(
        override val id: String,
        override val createdAt: Instant,
        val kind: FailureUi = FailureUi.Unknown,
    ) : MessageUi
}

/** Preferences of a fresh workspace; equals the domain `DefaultRunSettings`. */
val DefaultSettingsUi: SettingsUi = SettingsUi("pulse-pro", EffortUi.High, ApprovalUi.Ask)

/**
 * Harnesses of the composer picker: enabled [options], committed connections by chat id and choices kept on the panes
 * of chats that have no session yet. [failedChats] are chats whose last connection change was not confirmed.
 */
@Immutable
data class HarnessChoicesUi(
    val options: ImmutableList<HarnessOptionUi> = persistentListOf(),
    val chats: ImmutableMap<String, ImmutableSet<String>> = persistentMapOf(),
    val panes: ImmutableMap<Int, ImmutableSet<String>> = persistentMapOf(),
    val failedChats: ImmutableSet<String> = persistentSetOf(),
)

/** An enabled harness of the library; [projectIds] restrict where it is offered (empty: everywhere). */
@Immutable
data class HarnessOptionUi(
    val id: String,
    val title: String,
    val description: String = "",
    val projectIds: ImmutableSet<String> = persistentSetOf(),
) {
    /** Project restrictions only control discovery, never automatic attachment. */
    fun isAvailableFor(projectId: String?): Boolean = projectIds.isEmpty() || projectId in projectIds
}

/** One harness of a pane's picker. */
@Immutable
data class HarnessChoiceUi(val id: String, val title: String, val isSelected: Boolean, val description: String = "")
