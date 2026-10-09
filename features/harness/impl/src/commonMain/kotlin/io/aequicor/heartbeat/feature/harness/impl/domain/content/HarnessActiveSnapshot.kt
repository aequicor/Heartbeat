package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
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
        val needsProject = workspace != null && enabled.any {
            it.scope is HarnessScope.Projects && it.id !in attached
        }
        val source = if (needsProject) {
            val restored = worktrees as? WorktreeState.Ready ?: return null
            restored.sourceProjectOf(checkNotNull(workspace)) ?: workspace.takeIf { projects?.contains(it) == true }
                ?: return null
        } else {
            null
        }
        return enabled.asSequence().filter {
            it.id in attached || when (val scope = it.scope) {
                HarnessScope.Attached -> false
                HarnessScope.Profile -> true
                is HarnessScope.Projects -> source in scope.projects
            }
        }.sortedBy { it.name.value }.take(HarnessLimits.ACTIVE_PER_SESSION).toList()
    }

    override fun toString(): String = "HarnessActiveSnapshot(***)"
}

/** Resolves trusted host routing, with a bounded wait for restored library and source-project evidence. */
internal fun interface HarnessActiveAccess {
    suspend fun active(workspace: WorkspaceRef?, session: SessionRef?): List<Harness>
}
