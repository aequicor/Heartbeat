package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Profile chats of the studio for hosts outside the studio screen (the Chromium side panel). Turns started here are
 * the profile's own: the studio screen shows them running, can stop them and answer their permissions, and they
 * survive the caller going away. Only reading, sending, stopping and answering offered permissions are exposed;
 * chat metadata and configuration stay with the studio. Unavailable while `ai_studio.engine_runtime` is off.
 */
public interface StudioChats {
    /** Whether profile chats exist (`ai_studio.engine_runtime`). */
    public val availability: Flow<Boolean>

    /** Chats of the profile, pinned first, then by recent activity; empty while unavailable. */
    public fun observeList(): Flow<StudioChatList>

    /** Transcript of [chatId] in chronological order; empty for an unknown chat. Host directives are removed. */
    public fun observeTranscript(chatId: String): Flow<List<StudioChatEntry>>

    /** Starts a turn; returns once the engine accepted it or the request was refused. */
    public suspend fun send(request: StudioChatSend): StudioChatSendResult

    /** Requests interruption of the running turn of [chatId]; `false` when nothing runs there. */
    public suspend fun stop(chatId: String): Boolean

    /**
     * Answers a permission currently offered in [chatId] with one of its options; `false` when the request or the
     * option is no longer offered.
     */
    public suspend fun respond(
        chatId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer? = null,
    ): Boolean
}

/** Projects, chats and the permissions waiting for an answer. */
public data class StudioChatList(
    val projects: List<StudioChatProject> = emptyList(),
    val chats: List<StudioChatSummary> = emptyList(),
    val permissions: List<StudioPermission> = emptyList(),
)

/** A project chats can belong to. */
public data class StudioChatProject(val id: String, val name: String)

/** One chat of the list; helper and archived chats are not listed. */
public data class StudioChatSummary(
    val id: String,
    val title: String,
    val projectId: String?,
    val updatedAt: Instant,
    val isRunning: Boolean,
    val isPinned: Boolean,
    val isUnread: Boolean,
    /** A new prompt can continue the chat. */
    val isContinuable: Boolean,
)

/** One entry of a chat transcript. */
public sealed interface StudioChatEntry {
    /** Stable id within the chat. */
    public val id: String

    /** When the entry was written. */
    public val createdAt: Instant

    /** A prompt of the user. */
    public data class Prompt(
        override val id: String,
        override val createdAt: Instant,
        val text: String,
        val attachmentCount: Int,
    ) : StudioChatEntry

    /** An agent answer, updated in place while [isStreaming]. */
    public data class Reply(
        override val id: String,
        override val createdAt: Instant,
        val parts: List<StudioChatPart>,
        val isStreaming: Boolean,
    ) : StudioChatEntry

    /** A divider: the run stopped or failed, or the context was compacted. */
    public data class Notice(override val id: String, override val createdAt: Instant, val kind: StudioChatNotice) :
        StudioChatEntry
}

/** Content of a reply in its original order. */
public sealed interface StudioChatPart {
    /** Prose. */
    public data class Text(val text: String) : StudioChatPart

    /** Reasoning the engine exposed. */
    public data class Reasoning(val text: String) : StudioChatPart

    /** A tool call; [output] is its literal console output. */
    public data class Tool(val id: String, val title: String, val status: StudioChatToolStatus, val output: String) :
        StudioChatPart
}

/** Progress of a tool call. */
public enum class StudioChatToolStatus { Pending, Running, Done, Failed, Cancelled }

/** Kind of a transcript divider. */
public enum class StudioChatNotice { Stopped, Failed, Compacted, RolledOver }

/**
 * A prompt for [chatId], or for a new chat in [projectId] (`null`: without a project) when [chatId] is `null`.
 * New chats use the studio's default model; existing chats keep their own model and approval.
 */
public data class StudioChatSend(
    val chatId: String?,
    val projectId: String?,
    val prompt: String,
    val attachments: List<ResourceRef> = emptyList(),
)

/** Result of [StudioChats.send]. */
public sealed interface StudioChatSendResult {
    /** The engine accepted the turn in [chatId]. */
    public data class Accepted(val chatId: String) : StudioChatSendResult

    /** The turn was not started. */
    public data class Rejected(val reason: StudioChatRejection) : StudioChatSendResult
}

/** Why a prompt was not started. */
public enum class StudioChatRejection {
    /** Profile chats are off. */
    Unavailable,

    /** The chat does not exist or is archived. */
    UnknownChat,

    /** The project does not exist. */
    UnknownProject,

    /** The chat cannot take new prompts. */
    NotContinuable,

    /** A turn already runs in the chat. */
    Busy,

    /** The engine refused or failed the turn before accepting it. */
    Failed,
}
