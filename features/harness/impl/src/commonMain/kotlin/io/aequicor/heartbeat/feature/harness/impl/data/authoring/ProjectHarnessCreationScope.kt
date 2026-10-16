package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.sourceProjectOf
import kotlinx.coroutines.flow.first

/** Worktree harnesses belong to their original project, including after the managed checkout is removed. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProjectHarnessCreationScope(
    private val machines: MachineRegistry,
    private val workspaces: LocalWorkspaces,
) : HarnessCreationScope {
    override suspend fun resolve(workspace: WorkspaceRef?): HarnessScope? {
        if (workspace == null) return HarnessScope.Attached
        val worktrees = machines.find(WorktreeMachineKey)?.state?.value as? WorktreeState.Ready
        worktrees?.sourceProjectOf(workspace)?.let { return HarnessScope.Projects(setOf(it)) }
        val isSaved = workspaces.isAvailable && workspaces.observe().first().any { it.ref == workspace }
        return if (isSaved) HarnessScope.Projects(setOf(workspace)) else null
    }
}
