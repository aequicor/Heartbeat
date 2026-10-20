package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict

/** Text-only service sessions have no tools. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class AgentSafetyHook(private val isolation: AgentSafetyIsolation) : SessionHook {
    override val isIntercepting: Boolean get() = isolation.isActive
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict =
        if (isolation.contains(call.context.session) ||
            call.context.request?.value?.startsWith(ISOLATED_REQUEST_PREFIX) == true
        ) {
            ToolHookVerdict.Deny("Service sessions only read the supplied text; tools are unavailable.")
        } else {
            ToolHookVerdict.Continue
        }
}
