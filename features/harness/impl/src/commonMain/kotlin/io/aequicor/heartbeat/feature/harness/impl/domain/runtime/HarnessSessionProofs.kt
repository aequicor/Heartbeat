package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.applicableHarnesses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-workspace evidence comes only from trusted facade callbacks, never script arguments or event payload.
 * Open/Closed notifications are lossy and therefore do not grant or revoke authority here; the facade already
 * checks owner and accepted-turn lifetime. Every read resolves current library/project snapshots again.
 * Every trusted entry point remembers its route before reading it, so the proof cache is bounded: the oldest
 * known routes are forgotten first, while a conflict is kept as long as any known route can be evicted instead.
 */
internal class HarnessSessionProofs(
    private val library: () -> HarnessState,
    private val hasPublished: () -> Boolean = { true },
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
            val next = if (existing == null || existing == proposed) proposed else SessionWorkspaceProof.Conflicting
            if (existing == next) previous else (previous + (session to next)).bounded()
        }
    }

    override fun allows(harness: HarnessId, session: SessionRef): Boolean = harness in selected(session).active

    /** Only explicitly connected harnesses with a trusted session route can restrict tool calls. */
    override fun restricts(harness: HarnessId, session: SessionRef): Boolean = harness in selected(session).applicable

    /**
     * False means hook applicability is unknown and tool interception must conservatively require a decision.
     * Without any published instance no script can observe the session, so nothing is unknown (for example while
     * the library is idle, loading or failed).
     */
    fun isResolved(session: SessionRef): Boolean = selected(session).isResolved || !hasPublished()

    private fun selected(session: SessionRef): SessionSelection {
        val state = library()
        if (state.isSuspended) return SessionSelection(isResolved = true)
        val ready = state as? HarnessState.Ready ?: return SessionSelection()
        if (!ready.isRuntimeAvailable) return SessionSelection(isResolved = true)
        val harnesses = ready.harnesses.map { it.harness }.filter {
            it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove
        }
        if (harnesses.isEmpty() || ready.attachments[session].isNullOrEmpty()) {
            return SessionSelection(isResolved = true)
        }
        if (sessions.value[session] !is SessionWorkspaceProof.Known) return SessionSelection()
        val applicable = applicableHarnesses(harnesses, ready.attachments, session, null)
            .mapTo(linkedSetOf()) { it.id }
        return SessionSelection(applicable.take(HarnessLimits.ACTIVE_PER_SESSION).toSet(), applicable, isResolved = true)
    }

}

/** Drops one oldest known route (or the oldest conflict when only conflicts remain) above the bound. */
private fun Map<SessionRef, SessionWorkspaceProof>.bounded(): Map<SessionRef, SessionWorkspaceProof> {
    if (size <= MAX_REMEMBERED_SESSIONS) return this
    val evicted = entries.firstOrNull { it.value is SessionWorkspaceProof.Known }?.key ?: keys.first()
    return this - evicted
}

private const val MAX_REMEMBERED_SESSIONS = 512

private sealed interface SessionWorkspaceProof {
    data class Known(val workspace: WorkspaceRef?) : SessionWorkspaceProof {
        override fun toString(): String = "SessionWorkspaceProof.Known(***)"
    }
    data object Conflicting : SessionWorkspaceProof
}

private data class SessionSelection(
    val active: Set<HarnessId> = emptySet(),
    val applicable: Set<HarnessId> = emptySet(),
    val isResolved: Boolean = false,
) {
    override fun toString(): String = "SessionSelection(***)"
}
