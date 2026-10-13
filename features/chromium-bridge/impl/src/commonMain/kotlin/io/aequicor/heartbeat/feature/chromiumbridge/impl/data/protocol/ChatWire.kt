package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import io.aequicor.heartbeat.feature.aistudio.api.StudioChatComposer
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatList
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatToolStatus
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `chat.watch` / `chat.stop` parameters. */
@Serializable
internal data class ChatRef(val chatId: String)

/** `watch.cancel` parameters and the result of every watch. */
@Serializable
internal data class WatchRef(val watch: String)

/**
 * `chat.send` parameters: an existing [chatId], or a new chat in [projectId] running on [settings] (model and
 * approvals; else the start page's); [attachments] are captured pages.
 */
@Serializable
internal data class ChatSendParams(
    val chatId: String? = null,
    val projectId: String? = null,
    val text: String,
    val attachments: List<WirePage> = emptyList(),
    val settings: WireSettings? = null,
)

/** A page the extension captured: its address, title, the user's selection and its readable text. */
@Serializable
internal data class WirePage(
    val kind: String = "page",
    val url: String,
    val title: String = "",
    val text: String = "",
    val selection: String? = null,
) {
    /** Bounds the extension promised to respect. */
    fun isValid(): Boolean = kind == "page" && url.length <= ChatWireLimits.MAX_URL_CHARS &&
        (url.startsWith("https://") || url.startsWith("http://")) &&
        title.length <= ChatWireLimits.MAX_TITLE_CHARS && text.length <= ChatWireLimits.MAX_PAGE_CHARS &&
        (selection?.length ?: 0) <= ChatWireLimits.MAX_SELECTION_CHARS
}

/** `chat.send` result. */
@Serializable
internal data class ChatSent(val chatId: String)

/** `chat.stop` result. */
@Serializable
internal data class ChatStopped(
    @SerialName("stopped") val isStopped: Boolean,
)

/** `permission.respond` parameters; [answer] carries chosen choices or text for structured inputs. */
@Serializable
internal data class PermissionAnswerParams(
    val chatId: String,
    val requestId: String,
    val optionId: String,
    val answer: WireAnswer? = null,
)

/** A structured answer. */
@Serializable
internal data class WireAnswer(val selected: List<String>? = null, val text: String? = null) {
    fun toAnswer(): StudioPermissionAnswer? = when {
        selected != null -> StudioPermissionAnswer.Selected(selected)
        text != null -> StudioPermissionAnswer.Text(text)
        else -> null
    }
}

/** `chats.changed`: the whole list, replaced on every change. */
@Serializable
internal data class ChatsChanged(
    val watch: String,
    val projects: List<WireProject>,
    val chats: List<WireChat>,
    val permissions: List<WirePermission>,
    /** Chats beyond the cap, archived and oldest first, left out. */
    val omitted: Int = 0,
    val composer: WireComposer? = null,
)

/** A project. */
@Serializable
internal data class WireProject(val id: String, val name: String)

/** A chat of the list; times are epoch milliseconds. */
@Serializable
internal data class WireChat(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val updatedAt: Long,
    @SerialName("running") val isRunning: Boolean,
    @SerialName("pinned") val isPinned: Boolean,
    @SerialName("unread") val isUnread: Boolean,
    @SerialName("continuable") val isContinuable: Boolean,
    @SerialName("archived") val isArchived: Boolean = false,
    /** The chat's model route; absent before its first turn. */
    val modelId: String? = null,
    /** Settings the chat's engine confirmed. */
    val settings: WireSettings? = null,
    /** A settings change is in flight. */
    @SerialName("configuring") val isConfiguring: Boolean = false,
)

/** A permission waiting for an answer. */
@Serializable
internal data class WirePermission(
    val chatId: String,
    val requestId: String,
    val title: String,
    val description: String? = null,
    val options: List<WireOption>,
    val input: WireInput? = null,
)

/** One option of a permission or a choice of its input. */
@Serializable
internal data class WireOption(
    val id: String,
    val title: String,
    @SerialName("skip") val isSkip: Boolean = false,
)

/** Structured input of a permission: `single`, `multi` or `text`. */
@Serializable
internal data class WireInput(
    val kind: String,
    val choices: List<WireOption>? = null,
    val min: Int? = null,
    val max: Int? = null,
    val placeholder: String? = null,
    @SerialName("multiline") val isMultiline: Boolean? = null,
)

/** `chat.changed`: what changed in the transcript since the previous event of the same watch. */
@Serializable
internal data class ChatChanged(
    val watch: String,
    val chatId: String,
    /** Replace everything shown with [upserts]. */
    @SerialName("reset") val isReset: Boolean,
    val upserts: List<WireEntry>,
    val removed: List<String> = emptyList(),
    /** Earlier entries beyond the cap. */
    val olderOmitted: Int = 0,
)

/** One transcript entry: `prompt`, `reply` or `notice`. */
@Serializable
internal data class WireEntry(
    val id: String,
    val type: String,
    val createdAt: Long,
    val text: String? = null,
    val attachments: Int? = null,
    val parts: List<WirePart>? = null,
    @SerialName("streaming") val isStreaming: Boolean? = null,
    val notice: String? = null,
    @SerialName("truncated") val isTruncated: Boolean? = null,
)

/** One reply part: `text`, `reasoning` or `tool`. */
@Serializable
internal data class WirePart(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val title: String? = null,
    val status: String? = null,
    val output: String? = null,
    @SerialName("truncated") val isTruncated: Boolean? = null,
)

/** Size caps of what reaches the side panel. */
internal object ChatWireLimits {
    const val MAX_CHATS = 300
    const val MAX_ENTRIES = 200

    /** Leave room for a delta's removed ids plus its upserts inside the receiver's 16 MiB frame cap. */
    const val MAX_TRANSCRIPT_BYTES = 7 * 1024 * 1024

    /** A single reply remains readable even when it contains thousands of tool/reasoning parts. */
    const val MAX_REPLY_PARTS_BYTES = 512 * 1024
    const val MAX_TEXT_CHARS = 64 * 1024
    const val MAX_OUTPUT_CHARS = 4 * 1024
    const val MAX_TITLE_CHARS = 512
    const val MAX_URL_CHARS = 4096
    const val MAX_PAGE_CHARS = 200_000
    const val MAX_SELECTION_CHARS = 20_000
    const val MAX_PAGES = 4
}

/**
 * The list as sent: projects, the chats up to the cap (archived ones yield first) with the composer and live
 * permissions.
 */
internal fun StudioChatList.toWire(watch: String, composer: StudioChatComposer? = null): ChatsChanged = ChatsChanged(
    watch = watch,
    projects = projects.map { WireProject(it.id, it.name.take(ChatWireLimits.MAX_TITLE_CHARS)) },
    chats = chats.asSequence().sortedBy { it.isArchived }.take(ChatWireLimits.MAX_CHATS).map {
        WireChat(
            id = it.id,
            title = it.title.take(ChatWireLimits.MAX_TITLE_CHARS),
            projectId = it.projectId,
            updatedAt = it.updatedAt.toEpochMilliseconds(),
            isRunning = it.isRunning,
            isPinned = it.isPinned,
            isUnread = it.isUnread,
            isContinuable = it.isContinuable,
            isArchived = it.isArchived,
            modelId = it.modelId,
            settings = it.settings?.toWire(),
            isConfiguring = it.isConfiguring,
        )
    }.toList(),
    permissions = permissions.map { it.toWire() },
    omitted = (chats.size - ChatWireLimits.MAX_CHATS).coerceAtLeast(0),
    composer = composer?.toWire(),
)

private fun StudioPermission.toWire() = WirePermission(
    chatId = sessionId,
    requestId = requestId,
    title = title,
    description = description,
    options = options.map { it.toWire() },
    input = when (val value = input) {
        null -> null

        is StudioPermissionInput.SingleChoice -> WireInput("single", value.choices.map { it.toWire() })

        is StudioPermissionInput.MultiChoice -> WireInput(
            "multi",
            value.choices.map { it.toWire() },
            value.min,
            value.max,
        )

        is StudioPermissionInput.FreeText -> WireInput(
            "text",
            placeholder = value.placeholder,
            isMultiline = value.isMultiline,
        )
    },
)

private fun StudioPermissionOption.toWire() = WireOption(id, title, isSkip)

/** A transcript entry as sent; long prose keeps its beginning, tool output its end. */
internal fun StudioChatEntry.toWire(): WireEntry = when (this) {
    is StudioChatEntry.Prompt -> WireEntry(
        id = id,
        type = "prompt",
        createdAt = createdAt.toEpochMilliseconds(),
        text = text.take(ChatWireLimits.MAX_TEXT_CHARS),
        attachments = attachmentCount,
        isTruncated = (text.length > ChatWireLimits.MAX_TEXT_CHARS).takeIf { it },
    )

    is StudioChatEntry.Reply -> toBoundedReply()

    is StudioChatEntry.Notice -> WireEntry(
        id = id,
        type = "notice",
        createdAt = createdAt.toEpochMilliseconds(),
        notice = when (kind) {
            StudioChatNotice.Stopped -> "stopped"
            StudioChatNotice.Failed -> "failed"
            StudioChatNotice.Compacted -> "compacted"
            StudioChatNotice.RolledOver -> "rolled_over"
        },
    )
}

private fun StudioChatPart.toWire(): WirePart = when (this) {
    is StudioChatPart.Text -> WirePart(
        "text",
        text = text.take(ChatWireLimits.MAX_TEXT_CHARS),
        isTruncated = (text.length > ChatWireLimits.MAX_TEXT_CHARS).takeIf { it },
    )

    is StudioChatPart.Reasoning -> WirePart(
        "reasoning",
        text = text.take(ChatWireLimits.MAX_TEXT_CHARS),
        isTruncated = (text.length > ChatWireLimits.MAX_TEXT_CHARS).takeIf { it },
    )

    is StudioChatPart.Tool -> WirePart(
        "tool",
        id = id,
        title = title.take(ChatWireLimits.MAX_TITLE_CHARS),
        status = when (status) {
            StudioChatToolStatus.Pending -> "pending"
            StudioChatToolStatus.Running -> "running"
            StudioChatToolStatus.Done -> "done"
            StudioChatToolStatus.Failed -> "failed"
            StudioChatToolStatus.Cancelled -> "cancelled"
        },
        output = output.takeLast(ChatWireLimits.MAX_OUTPUT_CHARS),
        isTruncated = (output.length > ChatWireLimits.MAX_OUTPUT_CHARS).takeIf { it },
    )
}

/** Keeps the recent parts of a reply within a byte budget, counting JSON escaping and UTF-8. */
private fun StudioChatEntry.Reply.toBoundedReply(): WireEntry {
    val recent = mutableListOf<WirePart>()
    var bytes = 0
    for (part in parts.asReversed()) {
        val wire = part.toWire()
        val size = BridgeProtocol.json.encodeToString(WirePart.serializer(), wire).encodeToByteArray().size + 1
        if (bytes + size > ChatWireLimits.MAX_REPLY_PARTS_BYTES) break
        recent += wire
        bytes += size
    }
    return WireEntry(
        id = id,
        type = "reply",
        createdAt = createdAt.toEpochMilliseconds(),
        parts = recent.asReversed(),
        isStreaming = isStreaming,
        isTruncated = (recent.size < parts.size).takeIf { it },
    )
}

/**
 * Recent transcript entries fitting both the count and serialized byte caps. Windowing happens before diffing,
 * so resets and deltas describe the same bounded history; omitted older entries are reported to the panel.
 */
internal fun List<StudioChatEntry>.wireWindow(): List<WireEntry> {
    val recent = mutableListOf<WireEntry>()
    var bytes = 0
    for (entry in asReversed().take(ChatWireLimits.MAX_ENTRIES)) {
        val wire = entry.toWire()
        val size = BridgeProtocol.json.encodeToString(WireEntry.serializer(), wire).encodeToByteArray().size + 1
        if (bytes + size > ChatWireLimits.MAX_TRANSCRIPT_BYTES) break
        recent += wire
        bytes += size
    }
    return recent.asReversed()
}
