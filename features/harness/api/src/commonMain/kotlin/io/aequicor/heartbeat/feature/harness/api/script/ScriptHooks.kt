package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict

/**
 * Hooks can only add context or tighten the normal host policy. The runtime rejects sessions.send/spawn and
 * workflows.start from a hook, including work launched through inherited coroutine context. No verdict grants
 * permission. Only opted-in sessions where this harness is active are exposed.
 */
public interface ScriptHooks {
    /** Returns extra context, never replacement prompt text; null adds nothing. Text is private user data. */
    public fun beforePrompt(handler: suspend (SessionHookContext, String) -> String?): ScriptRegistration

    /** Continue keeps the ordinary trust gate; Deny refuses and Ask requires a user decision. */
    public fun beforeTool(handler: suspend (HookedToolCall) -> ToolHookVerdict): ScriptRegistration

    /** Returns context prepended to a tool result; the original result remains intact. */
    public fun afterTool(handler: suspend (HookedToolCall, ScriptToolResult) -> String?): ScriptRegistration
}
