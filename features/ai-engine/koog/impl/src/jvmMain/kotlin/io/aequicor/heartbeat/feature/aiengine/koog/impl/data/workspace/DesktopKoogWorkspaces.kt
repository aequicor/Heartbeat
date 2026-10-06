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
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.koogHostedTools
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.koogToolScope

/** Desktop Koog uses the same project tools and trust gate as native adapters. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopKoogWorkspaces(private val tools: ProfileAgentTools) : KoogWorkspaces {
    override val hasHostedTools: Boolean = true

    // Opening without a trusted accepted turn never grants coding authority.
    override suspend fun open(ref: WorkspaceRef): KoogWorkspace? = null

    override suspend fun open(ref: WorkspaceRef, context: AgentToolContext): KoogWorkspace? {
        require(context.workspace == ref)
        val scope = context.koogToolScope()
        val specs = tools.specifications(scope)
        if (specs.isEmpty()) return null
        val instructions = tools.instructions(scope.copy(declared = specs.mapTo(mutableSetOf()) { it.name }))
        return KoogWorkspace(koogHostedTools(specs, tools, context), instructions)
    }

    override suspend fun openDetached(context: AgentToolContext): KoogWorkspace? = detachedKoogWorkspace(tools, context)
}
