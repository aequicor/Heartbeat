package io.aequicor.heartbeat.feature.aiengine.facade.api

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
