@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant

/** Screen phase derived from the studio machine. */
@Serializable
@SerialName("StudioPhase")
enum class StudioPhase { Loading, Ready, Disabled, Error }

/** Which list the sidebar shows. */
@Serializable
@SerialName("SidebarMode")
enum class SidebarMode { Workspace, Archive }

/** A project row of the sidebar. */
@Serializable
@SerialName("ProjectUi")
@Immutable
data class ProjectUi(val id: String, val name: String, val environment: EnvironmentUi, val branch: String)

/** A pending scheduler wake, distinct from a currently executing engine turn. */
@Serializable
@SerialName("SessionWaitUi")
enum class SessionWaitUi { Sleeping, WaitingForEvent }

/** A session row; execution and scheduled waits come from their machines, metadata from the repository. */
@Serializable
@SerialName("SessionUi")
@Immutable
data class SessionUi(
    val id: String,
    val title: String,
    val projectId: String?,
    val updatedAt: Instant,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val isArchived: Boolean = false,
    val branch: String? = null,
    val isRunning: Boolean = false,
    val modelId: String? = null,
    val isContinuable: Boolean = true,
    val isWorktree: Boolean = false,
    /** An organic AI organism of the same id drives this chat; the chat only shows its sessions. */
    val isOrganism: Boolean = false,
    val isAwaitingChecklist: Boolean = false,
    val isReady: Boolean = false,
    val scheduledWait: SessionWaitUi? = null,
    val parentChatId: String? = null,
    /** The conversation is a restricted helper, including when it has no parent chat. */
    val isHelper: Boolean = false,
    /** Sidebar-only indentation, derived after filtering and grouping the visible helper tree. */
    val depth: Int = 0,
    val isAwaitingPermission: Boolean = false,
    /** A real parent exists outside the search filter; nested chats inherit that root's sidebar section. */
    val isNestedInSidebar: Boolean = false,
    /** Actual execution directory reference, which differs from [projectId] for worktree chats. */
    val executionWorkspace: String? = null,
) {
    /** Work is pending even when no engine turn is currently executing. */
    val isWaiting: Boolean get() = isAwaitingPermission || scheduledWait != null || isAwaitingChecklist
}

/** Title being edited inline; [origin] is the list row or pane header hosting the field. */
@Serializable
@SerialName("RenameUi")
@Immutable
data class RenameUi(val sessionId: String, val title: String, val origin: String)

/** Local sidebar presentation: none of it is business state. */
@Serializable
@SerialName("SidebarUi")
@Immutable
data class SidebarUi(
    val mode: SidebarMode = SidebarMode.Workspace,
    val query: String = "",
    val isSearchVisible: Boolean = false,
    val collapsedProjects: ImmutableSet<String> = persistentSetOf(),
    val isProjectsExpanded: Boolean = true,
    val isRecentExpanded: Boolean = true,
    val renaming: RenameUi? = null,
    val isVisible: Boolean = true,
    val isDrawerOpen: Boolean = false,
    /** Projects whose session lists show all roots instead of the five newest. */
    val expandedProjectSessions: ImmutableSet<String> = persistentSetOf(),
    val isNoProjectExpanded: Boolean = true,
)

/** Apply this caret once, after the editor displays [text]; [id] is unique across this screen's drafts. */
@Serializable
@SerialName("ComposerCaretRequestUi")
@Immutable
data class ComposerCaretRequestUi(val id: Long, val text: String, val caret: Int)

/** What a suggestion completes; the section label and icon derive from kind and origin. */
@Serializable
@SerialName("SuggestionKindUi")
enum class SuggestionKindUi { Command, Skill, File }

/** Which parts of answers the feed hides (context settings): reasoning, tool calls. */
@Serializable
@SerialName("FeedCollapseUi")
@Immutable
data class FeedCollapseUi(val isReasoningCollapsed: Boolean = false, val isToolCallsCollapsed: Boolean = false)

/** Where a suggestion comes from: Heartbeat itself or a native engine (its engine id). */
@Serializable
@SerialName("SuggestionOriginUi")
@Immutable
data class SuggestionOriginUi(val engine: String?) {
    val isHeartbeat: Boolean get() = engine == null

    /** Named origins of the screen projection. */
    companion object {
        val Heartbeat = SuggestionOriginUi(null)
    }
}

/** One row of the composer suggestion popup; the API suggestion projected for the screen. */
@Serializable
@SerialName("SuggestionUi")
@Immutable
data class SuggestionUi(
    val id: String,
    val label: String,
    val description: String,
    val kind: SuggestionKindUi,
    val origin: SuggestionOriginUi,
    /** Text replacing the active token; empty for files, which complete from their path. */
    val insert: String,
    /** File suggestions the current model cannot take stay visible, greyed. */
    val isSupported: Boolean = true,
    val relativePath: String? = null,
    val location: String? = null,
    val sizeBytes: Long = 0,
    val mediaType: String? = null,
)

/** Suggestions of one pane's active composer token; [selectedIndex] follows keyboard navigation. */
@Serializable
@SerialName("ComposerSuggestionsUi")
@Immutable
data class ComposerSuggestionsUi(
    val items: ImmutableList<SuggestionUi>,
    val selectedIndex: Int,
    /** Token the list belongs to; Escape hides it until the token changes. */
    val token: String,
    /** Coordinator generation; acceptance and publication reject obsolete requests. */
    val requestId: Long = 0,
)

/** One actionable permission exposed by the current native session. */
@Serializable
@SerialName("PermissionUi")
@Immutable
data class PermissionUi(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val options: ImmutableList<PermissionOptionUi>,
    /** What exactly is being approved, e.g. the text of an instruction the agent wants to remember. */
    val description: String? = null,
)

/** Exact native choice identity, displayed without inventing approval policy. */
@Serializable
@SerialName("PermissionOptionUi")
@Immutable
data class PermissionOptionUi(val id: String, val title: String)
