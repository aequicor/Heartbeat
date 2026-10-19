package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonObject

/**
 * Native call decoded and classified by the adapter, never by the model-facing hosted dispatcher.
 * [covered] applies the adapter's trust rules in [AgentToolDecisionMode.Interactive]. Automatic safety instead
 * reviews the full invocation and honors [automaticDenial]; policy and hook denials always take precedence.
 * Paths and command must describe the entire action shown for approval, without truncation.
 */
public data class NativeToolCall(
    val name: String,
    val action: AgentToolAction,
    val paths: List<String> = emptyList(),
    val command: String? = null,
    val covered: (TrustLevel) -> Boolean,
    val arguments: JsonObject? = null,
    /** Actual execution directory pinned by the adapter, not a model-provided workspace claim. */
    val workingDirectory: String? = null,
    /** An adapter restriction that independent review cannot override, such as terminating the host. */
    val automaticDenial: String? = null,
    /**
     * Adapter-owned environment pinned to the actual command process, including runtime overrides. Null when
     * shell initialization, persistent state or an engine hook can change it before execution. Model input and
     * the Heartbeat process environment never establish this evidence on their own.
     */
    val commandEnvironment: NativeCommandEnvironment? = null,
) {
    override fun toString(): String = "NativeToolCall"
}

/**
 * Local evidence of the environment the native shell command will inherit, or a safety-equivalent projection
 * retaining every Git discovery/executable variable and marking excluded execution overrides as unavailable.
 * The adapter supplies it only when execution is bound to those values for the lifetime of this call, including
 * the review suspension; arbitrary model arguments never establish this evidence.
 * Values may contain credentials: never serialize them into approval payloads, transcripts, prompts or logs.
 * Safety inspectors may derive bounded, redacted facts; neither [toString] nor [NativeToolCall.toString] reveals
 * the values. A launcher environment is insufficient when the runtime later rewrites it or loads shell profiles.
 */
public class NativeCommandEnvironment(environment: Map<String, String>) {
    private val captured = environment.toMap()

    /** A defensive copy of the adapter-owned environment; caller mutations cannot change captured evidence. */
    public val environment: Map<String, String> get() = captured.toMap()

    override fun toString(): String = "NativeCommandEnvironment(***)"
}

/** The adapter may execute only after Allow and while its captured native turn is still active. */
public sealed interface NativeVerdict {
    /** Policy, hooks and the applicable safety or user decision permit this exact call in this turn. */
    public data object Allow : NativeVerdict

    /** Safe user-facing diagnostic; adapters must not treat a denied call as successful execution. */
    public data class Deny(val reason: String) : NativeVerdict {
        override fun toString(): String = "NativeVerdict.Deny"
    }
}

/** Preflight bound to the exact call and turn; automatic safety returns only [Allow] or [Deny]. */
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
