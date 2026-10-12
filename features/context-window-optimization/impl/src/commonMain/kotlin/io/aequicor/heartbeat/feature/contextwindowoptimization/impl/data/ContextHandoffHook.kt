package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX

/**
 * Refuses every tool call of a handoff summary turn ([HANDOFF_REQUEST_PREFIX]): the turn only writes the summary of
 * the work before a rollover, whatever the trust level of the chat.
 */
internal class ContextHandoffHook(private val isEnabled: () -> Boolean) : SessionHook {
    private val log = Log.tag("ContextHandoffHook")

    override val isIntercepting: Boolean get() = isEnabled()

    @HighFrequency
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
        val request = call.context.request?.value ?: return ToolHookVerdict.Continue
        if (!request.startsWith(HANDOFF_REQUEST_PREFIX)) return ToolHookVerdict.Continue
        log.v { "refuse a tool call of a handoff summary turn: native=${call.isNative}" }
        return ToolHookVerdict.Deny(REASON)
    }

    private companion object {
        const val REASON = "Tools are not available while the handoff summary is written; answer with the summary only"
    }
}
