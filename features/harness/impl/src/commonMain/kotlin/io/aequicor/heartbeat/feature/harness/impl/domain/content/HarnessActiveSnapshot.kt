package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState

/**
 * Current committed content and policy, independent of desktop script support. Null means incomplete evidence,
 * never an empty policy. A missing session must not borrow another session's attachments. Helpers are attached
 * durably before their first submission.
 */
internal data class HarnessActiveSnapshot(val isEnabled: Boolean, val library: HarnessState?) {
    /** Contributing harnesses: [applicable] capped at [HarnessLimits.ACTIVE_PER_SESSION] in name order. */
    fun select(session: SessionRef?): List<Harness>? = applicable(session)?.take(HarnessLimits.ACTIVE_PER_SESSION)

    /** Every connected harness in name order, uncapped: restrictions must not be displaced by earlier names. */
    fun applicable(session: SessionRef?): List<Harness>? = chosen { ready -> session?.let { ready.attachments[it] } }

    /** Enabled harnesses among [harnesses], uncapped in name order: the creator's choice for a session being opened. */
    fun opening(harnesses: Set<HarnessId>): List<Harness>? = chosen { harnesses }

    private fun chosen(ids: (HarnessState.Ready) -> Set<HarnessId>?): List<Harness>? {
        if (!isEnabled || library?.isSuspended == true) return emptyList()
        check(library !is HarnessState.Failed) { "Harness library is unavailable" }
        val ready = library as? HarnessState.Ready ?: return null
        val chosen = ids(ready).orEmpty()
        return ready.harnesses.map { it.harness }
            .filter { it.id in chosen && it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove }
            .sortedBy { it.name.value }
    }

    override fun toString(): String = "HarnessActiveSnapshot(***)"
}

/** Resolves connected harnesses from the restored library and trusted host routing. */
internal fun interface HarnessActiveAccess {
    /**
     * Contributing harnesses, at most [HarnessLimits.ACTIVE_PER_SESSION]. Only the session's connections count;
     * [workspace] is the caller's route and never activates a harness.
     */
    suspend fun active(workspace: WorkspaceRef?, session: SessionRef?): List<Harness>

    /**
     * Every covering harness, uncapped, for restrictions (Off, hosted_off). Overflow-free fakes may keep the
     * default; the production access resolves it separately from [active].
     */
    suspend fun applicable(workspace: WorkspaceRef?, session: SessionRef?): List<Harness> = active(workspace, session)

    /**
     * Enabled harnesses among [harnesses], uncapped: the trusted creator's choice ([AgentToolScope.preselected]) for a
     * session that has no ref yet, whose declarations are fixed at opening. Fakes without such sessions keep none.
     */
    suspend fun opening(harnesses: Set<HarnessId>): List<Harness> = emptyList()
}
