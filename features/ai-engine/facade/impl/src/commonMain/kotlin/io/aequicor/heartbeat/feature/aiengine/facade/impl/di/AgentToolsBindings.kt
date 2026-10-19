package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver

/** Profile dispatcher dependencies are explicit; test constructor defaults must not hide missing bindings. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object AgentToolsBindings {
    /** Shares the profile workspace registry with route resolution, including virtual sandbox providers. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun tools(
        contributions: Set<AgentToolContribution>,
        hooks: SessionHooks,
        policies: ToolPolicyResolver,
        offloads: Lazy<Set<ToolOutputOffload>>,
        safety: Lazy<AgentSafetyReviewer>,
        workspaces: LocalWorkspaces,
    ): ProfileAgentTools = DefaultAgentTools(contributions, hooks, policies, offloads, safety, workspaces)
}
