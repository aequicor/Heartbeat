package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** The speaker of a message; roles determine default horizontal placement. */
public enum class HbChatRole { User, Assistant, System, Tool }

/** Built-in content renderers. A bubble content slot can provide any other renderer. */
public enum class HbMessageKind { Text, Code, Tool, Notice, Markdown }

/** Visual delivery status, owned and updated by the caller. */
public enum class HbMessageStatus { Complete, Streaming, Error }

/** Automatic follows the role; explicit placement works for every message kind. */
public enum class HbMessageAlignment { Automatic, Start, Center, End }

/**
 * Per-message appearance without coupling the transcript to an agent protocol.
 * Unspecified colors inherit accessible theme colors. Width is a fraction of available space,
 * capped by the theme's message maximum width on large screens.
 * [isContentWidth] lets short, unsegmented text messages wrap their content within that width cap.
 */
@Immutable
public data class HbMessageAppearance(
    val tone: HbTone = HbTone.Neutral,
    val alignment: HbMessageAlignment = HbMessageAlignment.Automatic,
    val widthFraction: Float = DEFAULT_MESSAGE_WIDTH,
    val background: Color = Color.Unspecified,
    val foreground: Color = Color.Unspecified,
    val isContentWidth: Boolean = false,
    /**
     * Joins lazy segments into one padded, outlined studio answer with one header and footer.
     * A [HbChatRole.System] entry, such as a host-owned worktree card, keeps the answer column and insets
     * without the surface, author row or copy footer.
     */
    val isUnified: Boolean = false,
    /** Short user messages can omit an otherwise redundant author row. */
    val isAuthorVisible: Boolean = true,
) {
    init {
        require(widthFraction > 0f && widthFraction <= 1f) { "Message width must be in (0, 1]." }
    }
}

private const val DEFAULT_MESSAGE_WIDTH = 0.86f

/** Chronological answer parts. An exposed reasoning summary uses a reasoning-kind tool disclosure. */
@Immutable
public sealed interface HbMessagePart {
    public val id: String

    /** A prose segment at its original position within the answer. */
    @Immutable
    public data class Text(
        override val id: String,
        val text: String,
        val kind: HbMessageKind = HbMessageKind.Markdown,
    ) : HbMessagePart

    /** A tool invocation or exposed reasoning disclosure at its original position. */
    @Immutable
    public data class Tool(val call: HbToolCall) : HbMessagePart {
        override val id: String get() = call.id
    }
}

/**
 * Immutable display state. Keep [id] unchanged when streaming updates replace [text].
 * Labels, authors and content are caller-localized; the design system does not infer agent state.
 * [codeLanguage] selects syntax for [HbMessageKind.Code]; unrecognized or absent languages remain plain.
 */
@Immutable
public data class HbChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val role: HbChatRole = HbChatRole.Assistant,
    val kind: HbMessageKind = HbMessageKind.Text,
    val status: HbMessageStatus = HbMessageStatus.Complete,
    val appearance: HbMessageAppearance = HbMessageAppearance(),
    val label: String? = null,
    val toolCalls: ImmutableList<HbToolCall> = persistentListOf(),
    val codeLanguage: String? = null,
    /** When non-empty, replaces the legacy text-then-tools order without changing their identities. */
    val parts: ImmutableList<HbMessagePart> = persistentListOf(),
) {
    init {
        require(parts.map { it.id }.distinct().size == parts.size) { "Message part ids must be unique." }
        require(id.isNotBlank()) { "A chat message needs a stable non-blank id." }
        require(
            toolCalls.map { it.id }.toSet().size == toolCalls.size,
        ) { "Tool call ids must be unique within a message." }
    }
}

internal fun resolvedAlignment(message: HbChatMessage): HbMessageAlignment =
    when (val placement = message.appearance.alignment) {
        HbMessageAlignment.Automatic -> when (message.role) {
            HbChatRole.User -> HbMessageAlignment.End
            HbChatRole.System -> HbMessageAlignment.Center
            HbChatRole.Assistant, HbChatRole.Tool -> HbMessageAlignment.Start
        }

        HbMessageAlignment.Start, HbMessageAlignment.Center, HbMessageAlignment.End -> placement
    }

/** Host-owned entry aligned with unified answers but without their surface, author row and copy footer. */
internal val HbChatMessage.isHostEntry: Boolean get() = appearance.isUnified && role == HbChatRole.System

internal fun requireUniqueMessageIds(messages: List<HbChatMessage>) {
    require(messages.map { it.id }.toSet().size == messages.size) { "Chat message ids must be unique." }
}
