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
 */
@Immutable
public data class HbMessageAppearance(
    val tone: HbTone = HbTone.Neutral,
    val alignment: HbMessageAlignment = HbMessageAlignment.Automatic,
    val widthFraction: Float = DEFAULT_MESSAGE_WIDTH,
    val background: Color = Color.Unspecified,
    val foreground: Color = Color.Unspecified,
) {
    init {
        require(widthFraction > 0f && widthFraction <= 1f) { "Message width must be in (0, 1]." }
    }
}

private const val DEFAULT_MESSAGE_WIDTH = 0.86f

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
) {
    init {
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

internal fun requireUniqueMessageIds(messages: List<HbChatMessage>) {
    require(messages.map { it.id }.toSet().size == messages.size) { "Chat message ids must be unique." }
}
