package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonObject

/**
 * Native call decoded and classified by the adapter, never by the model-facing hosted dispatcher.
 * [covered] applies the adapter's trust rules (including workspace and host-preservation restrictions).
 * It may only decide whether a user decision can be skipped; policy and hook denials always take precedence.
 * Paths and command must describe the entire action shown for approval, without truncation.
 */
public data class NativeToolCall(
    val name: String,
    val action: AgentToolAction,
    val paths: List<String> = emptyList(),
    val command: String? = null,
    val covered: (TrustLevel) -> Boolean,
    val arguments: JsonObject? = null,
) {
    override fun toString(): String = "NativeToolCall"
}

/** The adapter may execute only after Allow and while its captured native turn is still active. */
public sealed interface NativeVerdict {
    /** Policy, hooks and the applicable user decision permit this exact call in this turn. */
    public data object Allow : NativeVerdict

    /** Safe user-facing diagnostic; adapters must not treat a denied call as successful execution. */
    public data class Deny(val reason: String) : NativeVerdict {
        override fun toString(): String = "NativeVerdict.Deny"
    }
}

/** Noninteractive policy, hook and trust check, bound to one exact native call and its captured turn. */
public sealed interface NativePreparation {
    /** Complete authorization, subject to the adapter's final native lifetime check. */
    public data object Allow : NativePreparation

    /** No interactive approval may override this refusal. */
    public data class Deny(val reason: String) : NativePreparation {
        override fun toString(): String = "NativePreparation.Deny"
    }

    /** A one-shot continuation of this exact preflight; not an execution permission. */
    public data class Ask(val confirmation: NativeConfirmation) : NativePreparation {
        override fun toString(): String = "NativePreparation.Ask"
    }
}

/**
 * Requests the decision without invoking the hook twice. The capability is bound to the original full input,
 * context and turn; it rechecks policy before and after permission and cannot be reused. The adapter must
 * discard it when native call identity or input changes and recheck its native turn before writing Allow.
 */
public fun interface NativeConfirmation {
    /** Consumes this confirmation and revalidates the captured turn and policy around the user decision. */
    public suspend fun authorize(): NativeVerdict
}
