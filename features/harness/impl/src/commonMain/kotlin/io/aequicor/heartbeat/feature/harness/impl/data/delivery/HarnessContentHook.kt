package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptAddition
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContent
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContextDelivery
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessScriptInstructionAccess

/**
 * Frozen engines receive complete receipt-bearing context blocks. Refreshed engines read the same projection
 * from their hosted instructions. This path deliberately does not depend on desktop script runtime admission.
 */
internal class HarnessContentHook(
    private val enabled: () -> Boolean,
    private val isRefreshed: (SessionRef) -> Boolean,
    private val access: Lazy<HarnessActiveAccess>,
    private val delivery: Lazy<HarnessContextDelivery>,
    private val scripts: Lazy<HarnessScriptInstructionAccess> = lazy { HarnessScriptInstructionAccess { "" } },
) : SessionHook {
    override val isIntercepting: Boolean get() = enabled()

    override suspend fun preparePrompt(
        context: SessionHookContext,
        text: String,
        contextRevision: String?,
    ): SessionPromptAddition? {
        if (!enabled() || text.startsWith('/') || isRefreshed(context.session)) return null
        val active = access.value.active(context.workspace, context.session)
        if (!enabled()) return null
        // Frozen adapter declarations are not exposed by a hook. An empty proven set prevents author guidance
        // from assuming that a tool added after native session creation is actually callable in that session.
        val dynamic = scripts.value.instructions(
            AgentToolScope(context.workspace, declared = emptySet(), session = context.session),
        )
        if (!enabled()) return null
        val isFullContextReadable = active.none { HarnessTools.CONTEXT in it.tools.hostedOff }
        return delivery.value.prepare(context, contextRevision, HarnessContent(active, isFullContextReadable), dynamic)
    }
}
