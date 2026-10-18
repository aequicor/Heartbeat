package io.aequicor.heartbeat.feature.aistudio.api

import kotlinx.serialization.Serializable

/** Confirmed session-local preferences; [modelId] is a studio route, never a native path. */
@Serializable
public data class StudioSessionSettings(
    val modelId: String,
    val reasoningEffort: String? = null,
    val approval: ApprovalMode = ApprovalMode.Ask,
)

/** Profile-owned settings with at most one change in flight for this conversation. */
public data class StudioSessionConfiguration(val applied: StudioSessionSettings, val pendingOperation: String? = null)

/** One change addressed to a conversation instead of global defaults. */
public sealed interface StudioSettingChange {
    /** Chooses a route; changing its engine/binding in an existing conversation is rejected. */
    public data class Model(val modelId: String) : StudioSettingChange

    /** Chooses effort for subsequent model requests; null restores the engine default. */
    public data class Effort(val effort: String?) : StudioSettingChange

    /** Chooses the policy for subsequent tool calls. */
    public data class Approval(val approval: ApprovalMode) : StudioSettingChange
}

/**
 * Implementation of the plan of a planning chat in the same chat: [settings] choose the engine route, effort and
 * approval of the implementation. A route of another engine or connection, or [isFreshContext], continues the chat
 * in a new native session; otherwise the current session switches its settings. [mode] may move the chat into an
 * isolated checkout of its project first. [prompt] is the user's first implementation message.
 */
public data class PlanImplementation(
    val revision: Long,
    val settings: StudioSessionSettings,
    val prompt: String,
    val isFreshContext: Boolean = false,
    val mode: PlanImplementationMode = PlanImplementationMode.Chat,
) {
    override fun toString(): String = "PlanImplementation(revision=$revision, fresh=$isFreshContext, mode=$mode)"
}

/** Where a plan is implemented. */
public enum class PlanImplementationMode {
    /** In the chat as it is: its project folder or isolated checkout. */
    Chat,

    /** In a new git worktree of the chat's project; the chat continues in a new native session there. */
    Worktree,

    /** In a new source-only sandbox of the chat's project, built in warm build slots. */
    Sandbox,
}
