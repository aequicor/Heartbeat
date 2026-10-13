@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import kotlin.time.Duration
import kotlin.time.Instant

/** Progress of an agent tool call. */
@Serializable
@SerialName("ToolStatusUi")
enum class ToolStatusUi { Pending, Running, Done, Failed, Cancelled }

/** A tool call of a reply: literal console [output] and an optional unified [diff]. */
@Serializable
@SerialName("ToolUi")
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
@Serializable
@SerialName("LearningCallUi")
@Immutable
data class LearningCallUi(val isSkillLoad: Boolean, val kind: LearningKindUi?, val title: String, val content: String)

/** Kind of an instruction the agent remembers. */
@Serializable
@SerialName("LearningKindUi")
enum class LearningKindUi { General, Model, Skill }

/** Chronological blocks inside one answer, retaining native identities across streaming updates. */
@Serializable
@SerialName("ReplyPartUi")
@Immutable
sealed interface ReplyPartUi {
    /** Stable identity within one rendered answer. */
    val id: String

    /** Readable prose positioned between agent tool calls. */
    @Serializable
    @SerialName("ReplyPartUi.Text")
    data class Text(override val id: String, val text: String) : ReplyPartUi

    /** Engine-exposed reasoning presented as a subordinate disclosure. */
    @Serializable
    @SerialName("ReplyPartUi.Reasoning")
    data class Reasoning(override val id: String, val text: String) : ReplyPartUi

    /** One invocation with its current status and output. */
    @Serializable
    @SerialName("ReplyPartUi.Tool")
    data class Tool(val tool: ToolUi) : ReplyPartUi {
        override val id: String get() = tool.id
    }

    /** [count] tool calls of the reply shown as one line while the feed collapses agent work. */
    @Serializable
    @SerialName("ReplyPartUi.CollapsedTools")
    data class CollapsedTools(override val id: String, val count: Int, val isRunning: Boolean) : ReplyPartUi
}

/** Screen-level class of a failed run, mirroring the domain failure kind. */
@Serializable
@SerialName("FailureUi")
enum class FailureUi { Limit, Context, Authentication, Network, Unknown }

/** One transcript entry as the screen shows it. */
@Serializable
@SerialName("MessageUi")
@Immutable
sealed interface MessageUi {
    /** Stable id within the session. */
    val id: String

    /** When the entry was written. */
    val createdAt: Instant

    /** Whether [createdAt] came from the engine and may be shown to the user. */
    val isTimestampKnown: Boolean get() = true

    /** A prompt of the user. */
    @Serializable
    @SerialName("MessageUi.Prompt")
    data class Prompt(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        override val isTimestampKnown: Boolean = true,
        val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    ) : MessageUi

    /** An agent answer, streamed while [isStreaming]. */
    @Serializable
    @SerialName("MessageUi.Reply")
    data class Reply(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        val tools: ImmutableList<ToolUi>,
        val isStreaming: Boolean,
        val parts: ImmutableList<ReplyPartUi> = persistentListOf(),
        override val isTimestampKnown: Boolean = true,
        val checklistIds: ImmutableList<String> = persistentListOf(),
        /** Cards of interfaces shown in this reply, in order. */
        val cardIds: ImmutableList<String> = persistentListOf(),
    ) : MessageUi {
        /** How this instance looks in a collapsed feed, computed once per settings; not part of equality. */
        @Transient
        val collapseMemo: CollapseMemo = CollapseMemo()
    }

    /** The user stopped the run after [elapsed]. */
    @Serializable
    @SerialName("MessageUi.Stopped")
    data class Stopped(override val id: String, override val createdAt: Instant, val elapsed: Duration) : MessageUi

    /** A context divider: compacted, or continued in a new session. */
    @Serializable
    @SerialName("MessageUi.ContextNotice")
    data class ContextNotice(override val id: String, override val createdAt: Instant, val isRollover: Boolean) :
        MessageUi

    /** The run failed. */
    @Serializable
    @SerialName("MessageUi.Failed")
    data class Failed(
        override val id: String,
        override val createdAt: Instant,
        val kind: FailureUi = FailureUi.Unknown,
    ) : MessageUi
}
