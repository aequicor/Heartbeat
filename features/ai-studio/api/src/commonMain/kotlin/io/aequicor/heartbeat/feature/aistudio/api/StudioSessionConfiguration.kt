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
 * approval of the implementation. A route of another engine or connection, [isFreshContext] or a [mode] that moves
 * the chat continues the chat in a new native session with the essence of the planning conversation; this does not
 * depend on the context rollover toggle of context optimization. Otherwise the current session switches its
 * settings. [mode] may move the chat into an isolated checkout of its project or make it the chat of an organism
 * first. [prompt] is the user's first implementation message; an organism takes it with the plan as its goal.
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

/**
 * Where a plan is implemented. A chat that already works in an isolated checkout implements there: [Worktree] and
 * [Sandbox] do not move it again.
 */
public enum class PlanImplementationMode {
    /** In the chat as it is: its project folder or isolated checkout. */
    Chat,

    /** In a new git worktree of the chat's project (needs worktree mode); the chat continues there. */
    Worktree,

    /**
     * In a new source-only sandbox of the chat's project, built in warm build slots. While sandboxes are turned off
     * in the profile, the worktree journal prepares an ordinary worktree instead and the chat records that.
     */
    Sandbox,

    /**
     * By an organic AI organism conceived in the chat with the prompt and the plan as its goal; the chat becomes its
     * chat. Needs organic AI and a chat outside an isolated checkout; a conception that fails leaves an ordinary chat.
     */
    Organism,
}
