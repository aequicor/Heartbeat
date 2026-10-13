/*
 * Chat messages of the side panel protocol (`chats.watch`, `chat.watch`, `chat.send`, `chat.stop`,
 * `permission.respond`). The app encodes them and the side panel of the extension decodes the same classes;
 * the service worker relays them unchanged. Changes inside protocol version 1 are additive.
 */
package io.aequicor.heartbeat.feature.chromiumbridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `chat.watch` / `chat.stop` parameters. */
@Serializable
public data class ChatRef(public val chatId: String)

/** `watch.cancel` parameters and the result of every watch. */
@Serializable
public data class WatchRef(public val watch: String)

/** `chat.send` parameters: an existing [chatId], or a new chat in [projectId]; [attachments] are captured pages. */
@Serializable
public data class ChatSendParams(
    public val chatId: String? = null,
    public val projectId: String? = null,
    public val text: String,
    public val attachments: List<WirePage> = emptyList(),
)

/** A page the extension captured: its address, title, the user's selection and its readable text. */
@Serializable
public data class WirePage(
    public val kind: String = "page",
    public val url: String,
    public val title: String = "",
    public val text: String = "",
    public val selection: String? = null,
) {
    /** Bounds the extension promised to respect. */
    public fun isValid(): Boolean = kind == "page" && url.length <= ChatWireLimits.MAX_URL_CHARS &&
        (url.startsWith("https://") || url.startsWith("http://")) &&
        title.length <= ChatWireLimits.MAX_TITLE_CHARS && text.length <= ChatWireLimits.MAX_PAGE_CHARS &&
        (selection?.length ?: 0) <= ChatWireLimits.MAX_SELECTION_CHARS
}

/** `chat.send` result. */
@Serializable
public data class ChatSent(public val chatId: String)

/** `chat.stop` result. */
@Serializable
public data class ChatStopped(
    @SerialName("stopped") public val isStopped: Boolean,
)

/** `permission.respond` parameters; [answer] carries chosen choices or text for structured inputs. */
@Serializable
public data class PermissionAnswerParams(
    public val chatId: String,
    public val requestId: String,
    public val optionId: String,
    public val answer: WireAnswer? = null,
)

/** A structured answer: chosen choices or text. */
@Serializable
public data class WireAnswer(public val selected: List<String>? = null, public val text: String? = null)

/** `chats.changed`: the whole list, replaced on every change. */
@Serializable
public data class ChatsChanged(
    public val watch: String,
    public val projects: List<WireProject>,
    public val chats: List<WireChat>,
    public val permissions: List<WirePermission>,
    /** Chats beyond the cap, oldest first, left out. */
    public val omitted: Int = 0,
)

/** A project. */
@Serializable
public data class WireProject(public val id: String, public val name: String)

/** A chat of the list; times are epoch milliseconds. */
@Serializable
public data class WireChat(
    public val id: String,
    public val title: String,
    public val projectId: String? = null,
    public val updatedAt: Long,
    @SerialName("running") public val isRunning: Boolean,
    @SerialName("pinned") public val isPinned: Boolean,
    @SerialName("unread") public val isUnread: Boolean,
    @SerialName("continuable") public val isContinuable: Boolean,
)

/** A permission waiting for an answer. */
@Serializable
public data class WirePermission(
    public val chatId: String,
    public val requestId: String,
    public val title: String,
    public val description: String? = null,
    public val options: List<WireOption>,
    public val input: WireInput? = null,
)

/** One option of a permission or a choice of its input. */
@Serializable
public data class WireOption(
    public val id: String,
    public val title: String,
    @SerialName("skip") public val isSkip: Boolean = false,
)

/** Structured input of a permission: `single`, `multi` or `text`. */
@Serializable
public data class WireInput(
    public val kind: String,
    public val choices: List<WireOption>? = null,
    public val min: Int? = null,
    public val max: Int? = null,
    public val placeholder: String? = null,
    @SerialName("multiline") public val isMultiline: Boolean? = null,
)

/** `chat.changed`: what changed in the transcript since the previous event of the same watch. */
@Serializable
public data class ChatChanged(
    public val watch: String,
    public val chatId: String,
    /** Replace everything shown with [upserts]. */
    @SerialName("reset") public val isReset: Boolean,
    public val upserts: List<WireEntry>,
    public val removed: List<String> = emptyList(),
    /** Earlier entries beyond the cap. */
    public val olderOmitted: Int = 0,
)

/** One transcript entry: `prompt`, `reply` or `notice`. */
@Serializable
public data class WireEntry(
    public val id: String,
    public val type: String,
    public val createdAt: Long,
    public val text: String? = null,
    public val attachments: Int? = null,
    public val parts: List<WirePart>? = null,
    @SerialName("streaming") public val isStreaming: Boolean? = null,
    public val notice: String? = null,
    @SerialName("truncated") public val isTruncated: Boolean? = null,
)

/** One reply part: `text`, `reasoning` or `tool`. */
@Serializable
public data class WirePart(
    public val type: String,
    public val text: String? = null,
    public val id: String? = null,
    public val title: String? = null,
    public val status: String? = null,
    public val output: String? = null,
    @SerialName("truncated") public val isTruncated: Boolean? = null,
)

/** Size caps of what reaches the side panel. */
public object ChatWireLimits {
    public const val MAX_CHATS: Int = 300
    public const val MAX_ENTRIES: Int = 200

    /** Leave room for a delta's removed ids plus its upserts inside the receiver's 16 MiB frame cap. */
    public const val MAX_TRANSCRIPT_BYTES: Int = 7 * 1024 * 1024

    /** A single reply remains readable even when it contains thousands of tool/reasoning parts. */
    public const val MAX_REPLY_PARTS_BYTES: Int = 512 * 1024
    public const val MAX_TEXT_CHARS: Int = 64 * 1024
    public const val MAX_OUTPUT_CHARS: Int = 4 * 1024
    public const val MAX_TITLE_CHARS: Int = 512
    public const val MAX_URL_CHARS: Int = 4096
    public const val MAX_PAGE_CHARS: Int = 200_000
    public const val MAX_SELECTION_CHARS: Int = 20_000
    public const val MAX_PAGES: Int = 4
}
