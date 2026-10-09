package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.activeHarnesses
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.sourceProjectOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-workspace evidence comes only from trusted facade callbacks, never script arguments or event payload.
 * Open/Closed notifications are lossy and therefore do not grant or revoke authority here; the facade already
 * checks owner and accepted-turn lifetime. Every read resolves current library/project snapshots again.
 */
internal class HarnessSessionProofs(
    private val library: () -> HarnessState,
    private val worktrees: () -> WorktreeState?,
    private val knownProjects: () -> Set<WorkspaceRef>?,
) : HarnessSessionAdmission {
    private val sessions = MutableStateFlow<Map<SessionRef, SessionWorkspaceProof>>(emptyMap())
    private val log = Log.tag("HarnessSessionProofs")

    /** A conflicting route remains ambiguous; another late callback cannot silently overwrite that conflict. */
    @HighFrequency
    fun remember(context: SessionHookContext) {
        remember(context.session, context.workspace)
    }

    /** Hosted scopes are also trusted adapter metadata; model arguments never enter this method. */
    @HighFrequency
    fun remember(session: SessionRef, workspace: WorkspaceRef?) {
        log.v { "remember trusted session route" }
        val proposed = SessionWorkspaceProof.Known(workspace)
        sessions.update { previous ->
            val existing = previous[session]
            previous + (
                session to when {
                    existing == null || existing == proposed -> proposed
                    else -> SessionWorkspaceProof.Conflicting
                }
            )
        }
    }

    override fun allows(harness: HarnessId, session: SessionRef): Boolean = harness in selected(session).active

    /** False means hook applicability is unknown and tool interception must conservatively require a decision. */
    fun isResolved(session: SessionRef): Boolean = selected(session).isResolved

    private fun selected(session: SessionRef): SessionSelection {
        val state = library()
        if (state.isSuspended) return SessionSelection(isResolved = true)
        val ready = state as? HarnessState.Ready ?: return SessionSelection()
        if (!ready.isRuntimeAvailable) return SessionSelection(isResolved = true)
        val harnesses = ready.harnesses.map { it.harness }.filter {
            it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove
        }
        if (harnesses.isEmpty()) return SessionSelection(isResolved = true)
        val proof = sessions.value[session] as? SessionWorkspaceProof.Known ?: return SessionSelection()
        val source = sourceProject(proof.workspace)
        val attached = ready.attachments[session].orEmpty()
        val project = (source as? SourceProjectProof.Known)?.workspace
        val known = activeHarnesses(harnesses, ready.attachments, session, project).mapTo(linkedSetOf()) { it.id }
        val possible = harnesses.filter { it.scope is HarnessScope.Projects && it.id !in attached }
        return if (source != SourceProjectProof.Unknown || possible.isEmpty()) {
            SessionSelection(known, isResolved = true)
        } else {
            // Unknown project candidates can displace only later names; certain earlier hooks still apply.
            val certain = (harnesses.filter { it.id in known } + possible).asSequence().distinctBy { it.id }
                .sortedBy { it.name.value }.take(HarnessLimits.ACTIVE_PER_SESSION).mapTo(linkedSetOf()) { it.id }
            SessionSelection(known.intersect(certain))
        }
    }

    private fun sourceProject(workspace: WorkspaceRef?): SourceProjectProof {
        if (workspace == null) return SourceProjectProof.Known(null)
        val restored = worktrees() as? WorktreeState.Ready
        val mapped = restored?.sourceProjectOf(workspace)
        // A checkout can be promoted into the ordinary catalog without removing its durable worktree mapping.
        return when {
            restored == null -> SourceProjectProof.Unknown
            mapped != null -> SourceProjectProof.Known(mapped)
            knownProjects()?.contains(workspace) == true -> SourceProjectProof.Known(workspace)
            else -> SourceProjectProof.Unknown
        }
    }
}

private sealed interface SessionWorkspaceProof {
    data class Known(val workspace: WorkspaceRef?) : SessionWorkspaceProof {
        override fun toString(): String = "SessionWorkspaceProof.Known(***)"
    }
    data object Conflicting : SessionWorkspaceProof
}

private sealed interface SourceProjectProof {
    data class Known(val workspace: WorkspaceRef?) : SourceProjectProof {
        override fun toString(): String = "SourceProjectProof.Known(***)"
    }
    data object Unknown : SourceProjectProof
}

private data class SessionSelection(val active: Set<HarnessId> = emptySet(), val isResolved: Boolean = false) {
    override fun toString(): String = "SessionSelection(***)"
}
