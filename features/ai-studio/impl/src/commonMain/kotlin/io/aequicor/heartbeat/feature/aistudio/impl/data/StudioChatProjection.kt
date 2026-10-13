package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatList
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatProject
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSummary
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatToolStatus
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ContextNoticeKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus

/** Chats an outside host may list: archived ones included, helpers excluded; pinned first, then recent. */
internal fun StudioWorkspace.toChatList(runtime: StudioRuntimeState): StudioChatList {
    val chats = sessions.asSequence()
        .filter { it.parentChatId == null }
        .sortedWith(compareByDescending<StudioSession> { it.isPinned }.thenByDescending { it.updatedAt })
        .map { session ->
            val configuration = runtime.configurations[session.id]
            StudioChatSummary(
                id = session.id,
                title = session.title,
                projectId = session.projectId,
                updatedAt = session.updatedAt,
                isRunning = session.id in runtime.running,
                isPinned = session.isPinned,
                isUnread = session.isUnread,
                isContinuable = session.isContinuable,
                isArchived = session.isArchived,
                modelId = configuration?.applied?.modelId ?: session.modelId?.takeIf(String::isNotBlank),
                settings = configuration?.applied?.let {
                    StudioChatSettings(it.modelId, it.reasoningEffort, it.approval)
                },
                isConfiguring = configuration?.pendingOperation != null,
            )
        }
        .toList()
    val listed = chats.mapTo(HashSet()) { it.id }
    return StudioChatList(
        projects = projects.map { StudioChatProject(it.id, it.name) },
        chats = chats,
        permissions = runtime.permissions.filter { it.sessionId in listed },
    )
}

/** The transcript as outside hosts see it: prompts without host directives, replies in their part order. */
internal fun List<StudioMessage>.toChatEntries(): List<StudioChatEntry> = map { message ->
    when (message) {
        is StudioMessage.Prompt -> StudioChatEntry.Prompt(
            message.id,
            message.createdAt,
            stripHostDirectives(message.text),
            message.attachments.size,
        )

        is StudioMessage.Reply -> StudioChatEntry.Reply(
            message.id,
            message.createdAt,
            message.chatParts(),
            message.isStreaming,
        )

        is StudioMessage.Stopped -> StudioChatEntry.Notice(message.id, message.createdAt, StudioChatNotice.Stopped)

        is StudioMessage.Failed -> StudioChatEntry.Notice(message.id, message.createdAt, StudioChatNotice.Failed)

        is StudioMessage.ContextNotice -> StudioChatEntry.Notice(
            message.id,
            message.createdAt,
            when (message.kind) {
                ContextNoticeKind.Compacted -> StudioChatNotice.Compacted
                ContextNoticeKind.RolledOver -> StudioChatNotice.RolledOver
            },
        )
    }
}

/** Ordered parts when the engine reported them, otherwise the text followed by the tools. */
private fun StudioMessage.Reply.chatParts(): List<StudioChatPart> = if (parts.isNotEmpty()) {
    parts.map { part ->
        when (part) {
            is StudioReplyPart.Text -> StudioChatPart.Text(part.text)
            is StudioReplyPart.Reasoning -> StudioChatPart.Reasoning(part.text)
            is StudioReplyPart.Tool -> part.tool.chatPart()
        }
    }
} else {
    buildList {
        if (text.isNotEmpty()) add(StudioChatPart.Text(text))
        tools.forEach { add(it.chatPart()) }
    }
}

private fun StudioToolRun.chatPart() = StudioChatPart.Tool(
    id = id,
    title = title,
    status = when (status) {
        ToolRunStatus.Pending -> StudioChatToolStatus.Pending
        ToolRunStatus.Running -> StudioChatToolStatus.Running
        ToolRunStatus.Done -> StudioChatToolStatus.Done
        ToolRunStatus.Failed -> StudioChatToolStatus.Failed
        ToolRunStatus.Cancelled -> StudioChatToolStatus.Cancelled
    },
    output = output,
)
