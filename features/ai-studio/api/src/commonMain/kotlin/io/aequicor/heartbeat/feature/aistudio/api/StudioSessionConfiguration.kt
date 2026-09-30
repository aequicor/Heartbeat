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
