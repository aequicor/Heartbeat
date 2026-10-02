package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.workspace

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogWorkspace
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogWorkspaces
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.detachedKoogWorkspace

/** Mobile Koog sessions are a plain chat: no local project, files or commands. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedKoogWorkspaces(private val tools: ProfileAgentTools) : KoogWorkspaces {
    override suspend fun open(ref: WorkspaceRef): KoogWorkspace? = null

    // Hosted tools without a project need no local files or processes.
    override suspend fun openDetached(context: AgentToolContext): KoogWorkspace? = detachedKoogWorkspace(tools, context)
}
