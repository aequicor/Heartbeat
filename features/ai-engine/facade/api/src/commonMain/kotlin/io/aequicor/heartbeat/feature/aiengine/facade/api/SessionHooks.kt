package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.JsonObject

/** Identity of one opted-in facade handle, assigned by the host rather than the model. */
public data class SessionOwner(val value: String)

/** Trusted origin of an interception; native tool callbacks may not carry a facade [turn]. */
public data class SessionHookContext(
    val session: SessionRef,
    val workspace: WorkspaceRef?,
    val request: RequestId?,
    val turn: TurnId?,
    val owner: SessionOwner,
) {
    override fun toString(): String = "SessionHookContext"
}

/** A tool call as seen by hooks; arguments are untrusted data and must never be logged. */
public data class HookedToolCall(
    val context: SessionHookContext,
    val name: String,
    val action: AgentToolAction,
    val arguments: JsonObject,
    val isNative: Boolean = false,
) {
    override fun toString(): String = "HookedToolCall"
}

/** A hook can only tighten authorization; Continue does not grant permission. Deny dominates Ask. */
public sealed interface ToolHookVerdict {
    /** Continue to the host's ordinary policy and trust gate. */
    public data object Continue : ToolHookVerdict

    /** Refuse the call irrespective of trust or a previous approval. */
    public data class Deny(val reason: String) : ToolHookVerdict {
        override fun toString(): String = "Deny"
    }

    /** Force a user decision, including for read operations and Full trust. */
    public data class Ask(val reason: String) : ToolHookVerdict {
        override fun toString(): String = "Ask"
    }
}

/** Opted-in session lifecycle. Only turns accepted by this [context]'s owner are observed. */
public sealed interface SessionLifecycle {
    /** Host-issued identity; never supplied by script code or model arguments. */
    public val context: SessionHookContext

    /** One handle opened; multiple handles may refer to the same native session. */
    public data class Opened(override val context: SessionHookContext) : SessionLifecycle

    /** Native submission was accepted by this owner. */
    public data class TurnStarted(override val context: SessionHookContext) : SessionLifecycle

    /** Authoritative end of an accepted turn. */
    public data class TurnFinished(override val context: SessionHookContext, val outcome: TurnOutcome) :
        SessionLifecycle {
        override fun toString(): String = "TurnFinished"
    }

    /** A new permission on an accepted turn; duplicate permission ids are observed once. */
    public data class PermissionRequested(
        override val context: SessionHookContext,
        val permission: PermissionRequest,
    ) : SessionLifecycle {
        override fun toString(): String = "PermissionRequested"
    }

    /** One handle closed; other handles and their turns retain their own opt-in. */
    public data class Closed(override val context: SessionHookContext) : SessionLifecycle
}

/**
 * Profile contribution outside the adapter SPI. Implementations can observe events and add prompt/tool context,
 * but cannot approve calls. Callbacks run off the caller's dispatcher and have bounded waiting times. Untrusted
 * exceptions must be sanitized by script implementations before crossing this boundary.
 */
public interface SessionHook {
    /** Whether lifecycle observations are wanted now. */
    public val isObserving: Boolean get() = false

    /** Whether interception is wanted now. */
    public val isIntercepting: Boolean get() = false

    /** Queued lifecycle notification; it must not block the caller. */
    public suspend fun observe(event: SessionLifecycle): Unit = Unit

    /** Additional context appended to the prompt, never a replacement of the user's text. */
    public suspend fun beforePrompt(context: SessionHookContext, text: String): String? = null

    /** Additional constraint applied before the ordinary authorization gate. */
    public suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = ToolHookVerdict.Continue

    /** Additional context prepended to the tool result. */
    public suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String? = null
}
