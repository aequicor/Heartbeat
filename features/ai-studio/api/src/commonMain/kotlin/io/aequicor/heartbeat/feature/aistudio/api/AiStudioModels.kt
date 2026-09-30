package io.aequicor.heartbeat.feature.aistudio.api

import kotlinx.serialization.Serializable

/** Reasoning budget the agent may spend on a run. */
public enum class ReasoningEffort { Low, Medium, High, VeryHigh }

/** How the agent treats actions with side effects, such as editing files. */
@Serializable
public enum class ApprovalMode {
    /** Only read-only actions run without an explicit confirmation. */
    Ask,

    /** File edits run without asking; commands and other actions with side effects still ask. */
    AutoEdits,

    /** Every action runs without asking. */
    AutoApprove,
}

/** New-conversation preferences; existing engine chats keep their own settings. [modelId] is a studio route. */
public data class RunSettings(val modelId: String, val effort: ReasoningEffort, val approval: ApprovalMode)

/** Values the studio starts with, resolved from the workspace and the model catalog. */
public data class StudioDefaults(
    val projectId: String?,
    val settings: RunSettings,
    val settingsVersion: StudioSettingsVersion = StudioSettingsVersion(),
)

/**
 * Orders preference writes from one loaded workspace. [writer] changes on every load; [revision] increases on
 * each explicit choice. Independent workspace instances never discard each other's writes as stale.
 */
public data class StudioSettingsVersion(val writer: String = "", val revision: Long = 0)

/**
 * One column of the workspace. Shows the session [sessionId], or the new-session page targeting [projectId]
 * (`null` — a conversation outside projects). [isCreating] is set while a submitted prompt creates its session.
 * [createRequestId] identifies that submission independently of the reusable pane [id]. It remains after a
 * failure until the page is replaced or another prompt is submitted, so delayed draft restoration is correlated.
 */
public data class StudioPane(
    val id: Int,
    val sessionId: String? = null,
    val projectId: String? = null,
    val isCreating: Boolean = false,
    val createRequestId: Long? = null,
    /** Fixed at creation; only a new local-project pane can change its execution mode. */
    val isWorktree: Boolean = false,
)

/** Metadata change of a session, persisted by the studio effects. */
public sealed interface SessionEdit {
    /** Replaces the title; blank titles are rejected by the machine. */
    public data class Rename(val title: String) : SessionEdit

    /** Moves the session into or out of the pinned group. */
    public data class SetPinned(val isPinned: Boolean) : SessionEdit

    /** Marks new agent output as seen or asks to revisit the session later. */
    public data class SetUnread(val isUnread: Boolean) : SessionEdit

    /** Hides the session from the main lists or restores it. */
    public data class SetArchived(val isArchived: Boolean) : SessionEdit
}

/** How an agent run ended. */
public enum class RunOutcome { Completed, Stopped, Failed }
