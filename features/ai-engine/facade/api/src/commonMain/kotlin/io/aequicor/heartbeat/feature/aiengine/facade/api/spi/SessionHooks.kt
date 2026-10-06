package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId

/** Profile dispatcher used by facade handles and engine adapters, not by feature contributions. */
public interface SessionHooks {
    /** Synchronously records ownership before queuing observations; deduplicates accepted turn events. */
    public fun observe(event: SessionLifecycle): Unit = Unit

    /**
     * Registers a non-null facade turn before native submission, which can invoke tools before send returns.
     * Native callbacks may omit the facade turn only when their request and owner match this binding.
     */
    public fun bindTurn(context: SessionHookContext): Unit = Unit

    /**
     * Revokes interception after the tool barrier. [isRejected] discards an unaccepted submission immediately;
     * accepted or still uncertain submissions retain observation until TurnFinished while their owner is open.
     * Closing the owner plus completing the barrier ends tracking, in either arrival order.
     */
    public fun releaseTurn(session: SessionRef, turn: TurnId, isRejected: Boolean = false): Unit = Unit

    /** Resolves only a bound, active, opted-in turn; no fallback to another handle of the session. */
    public fun context(session: SessionRef, request: RequestId?, turn: TurnId?): SessionHookContext? = null

    /** Prompt additions within a common two-second budget; failure adds no text. */
    public suspend fun beforePrompt(context: SessionHookContext, text: String): String? = null

    /** Deny > Ask > Continue; timeout or failure forces a decision within a common 1.2-second budget. */
    public suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = ToolHookVerdict.Continue

    /** Bounded additions before the tool result; failure adds no text. */
    public suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String? = null
}

/** Default when session interception is not installed. */
public object NoSessionHooks : SessionHooks
