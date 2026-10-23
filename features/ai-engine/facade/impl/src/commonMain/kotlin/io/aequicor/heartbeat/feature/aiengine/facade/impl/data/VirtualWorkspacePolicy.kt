package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch

/** Virtual workspaces expose only tools that preserve their revisioned file and execution boundaries. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class VirtualWorkspacePolicy(
    private val workspaces: LocalWorkspaces,
    private val engines: Lazy<EngineFacade>,
    private val tools: Lazy<Set<AgentToolContribution>>,
) : AgentToolPolicyProvider {
    override val isIsolation: Boolean = true

    override suspend fun policy(scope: ToolPolicyScope): ToolPolicy? {
        val workspace = scope.workspace ?: return null
        if (workspaces.virtual(workspace) == null) return null
        val engine = scope.engine ?: scope.target?.engine ?: scope.session?.engine
        val native = engines.value.engines.state.value.firstOrNull { it.descriptor.id == engine }
            ?.descriptor?.nativeTools.orEmpty().filterNot { it.isGated && it.isWorkspaceFree }
            .associate { it.name to ToolSwitch.Off }
        return ToolPolicy(
            hostedOff = tools.value.asSequence().flatMap { it.catalog }.map { it.name }.filterNot(
                ::virtualToolAllowed,
            ).toSet(),
            native = native,
        )
    }
}

internal fun virtualToolAllowed(name: String): Boolean = name in setOf(
    "read_file", "list_dir", "glob", "grep", "write_file", "edit_file", "delete_file", "move_file",
    "configure_build", "run_build", "build_status", "cancel_build", "mark_task_complete",
    "sandbox_integrate", "sandbox_resolve_conflict", "sandbox_commit", "sandbox_checkout", "web_search", "web_fetch",
    "organism_divide", "organism_complain", "organism_dispute", "organism_status", "organism_receive",
    "subagent_spawn", "subagent_receive",
    "plan_read", "plan_write", "plan_propose",
)
