package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatList
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatToolStatus
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.ChatWireLimits
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.ChatsChanged
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WireAnswer
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WireChat
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WireEntry
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WireInput
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WireOption
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePart
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePermission
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WireProject

/** The list as sent: projects, the most recent chats up to the cap and live permissions. */
internal fun StudioChatList.toWire(watch: String): ChatsChanged = ChatsChanged(
    watch = watch,
    projects = projects.map { WireProject(it.id, it.name.take(ChatWireLimits.MAX_TITLE_CHARS)) },
    chats = chats.take(ChatWireLimits.MAX_CHATS).map {
        WireChat(
            id = it.id,
            title = it.title.take(ChatWireLimits.MAX_TITLE_CHARS),
            projectId = it.projectId,
            updatedAt = it.updatedAt.toEpochMilliseconds(),
            isRunning = it.isRunning,
            isPinned = it.isPinned,
            isUnread = it.isUnread,
            isContinuable = it.isContinuable,
        )
    },
    permissions = permissions.map { it.toWire() },
    omitted = (chats.size - ChatWireLimits.MAX_CHATS).coerceAtLeast(0),
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

/** The app's form of a structured answer. */
internal fun WireAnswer.toAnswer(): StudioPermissionAnswer? =
    selected?.let(StudioPermissionAnswer::Selected) ?: text?.let(StudioPermissionAnswer::Text)
