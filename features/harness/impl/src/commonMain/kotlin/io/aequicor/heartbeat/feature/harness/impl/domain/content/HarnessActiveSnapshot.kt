package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.sourceProjectOf

/**
 * Current committed content and policy, independent of desktop script support. Null means incomplete evidence,
 * never an empty policy. Before native session creation only permanent scopes apply; a missing session must not
 * borrow another session's attachments. Helpers are attached durably before their first submission.
 */
internal data class HarnessActiveSnapshot(
    val isEnabled: Boolean,
    val library: HarnessState?,
    val worktrees: WorktreeState?,
    val projects: Set<WorkspaceRef>?,
) {
    fun select(workspace: WorkspaceRef?, session: SessionRef?): List<Harness>? {
        if (!isEnabled || library?.isSuspended == true) return emptyList()
        check(library !is HarnessState.Failed) { "Harness library is unavailable" }
        val ready = library as? HarnessState.Ready ?: return null
        val enabled = ready.harnesses.map { it.harness }.filter {
            it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove
        }
        val attached = session?.let { ready.attachments[it] }.orEmpty()
        val isProjectScoped = enabled.any { it.scope is HarnessScope.Projects && it.id !in attached }
        val projectWorkspace = workspace?.takeIf { isProjectScoped }
        val source = if (projectWorkspace != null) sourceOf(projectWorkspace) else SourceProject(null)
        return source?.let { known ->
            enabled.asSequence().filter { it.isActive(attached, known.ref) }
                .sortedBy { it.name.value }.take(HarnessLimits.ACTIVE_PER_SESSION).toList()
        }
    }

    /** Null while worktrees are restoring or the folder is neither a checkout nor a saved project. */
    private fun sourceOf(workspace: WorkspaceRef): SourceProject? {
        val restored = worktrees as? WorktreeState.Ready ?: return null
        val source = restored.sourceProjectOf(workspace) ?: workspace.takeIf { projects?.contains(it) == true }
        return source?.let(::SourceProject)
    }

    override fun toString(): String = "HarnessActiveSnapshot(***)"
}

private data class SourceProject(val ref: WorkspaceRef?)

private fun Harness.isActive(attached: Set<HarnessId>, source: WorkspaceRef?): Boolean =
    id in attached || when (val scope = scope) {
        HarnessScope.Attached -> false
        HarnessScope.Profile -> true
        is HarnessScope.Projects -> source in scope.projects
    }

/** Resolves trusted host routing, with a bounded wait for restored library and source-project evidence. */
internal fun interface HarnessActiveAccess {
    suspend fun active(workspace: WorkspaceRef?, session: SessionRef?): List<Harness>
}
