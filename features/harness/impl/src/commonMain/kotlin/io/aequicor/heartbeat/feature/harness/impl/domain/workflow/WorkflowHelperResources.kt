package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Profile-owned live handles survive a paused/cancelled driver. Exactly one fenced driver or cleanup task may
 * own a run at a time. Different branches may acquire concurrently; no mutex is held while waiting for capacity.
 * A released handle stays here until its journal deletion is confirmed, so an uncertain deletion cannot cause
 * the already returned slot to be adopted again in the same process.
 */
internal class WorkflowHelperResources(private val helpers: HelperAgents, private val journal: WorkflowHelperJournal) {
    private val live = MutableStateFlow<Map<ActionId, WorkflowHelperResource>>(emptyMap())

    fun find(run: RunId, key: StepKey): WorkflowHelperResource? =
        live.value.values.singleOrNull { it.grant.run == run && it.grant.key == key }

    suspend fun records(run: RunId): List<WorkflowHelperGrant> =
        (journal.pending().filter { it.run == run } + live.value.values.filter { it.grant.run == run }.map { it.grant })
            .associateBy { it.reservation }.values.toList()

    /** [existing] is used only for a new acquisition that resumes an already journaled helper step. */
    suspend fun acquire(grant: WorkflowHelperGrant, existing: HelperId? = grant.helper): WorkflowHelperResource {
        live.value[grant.reservation]?.let { return it }
        val lease = helpers.acquire(ActionId(grant.run.value), grant.parent, existing, grant.reservation)
        // No suspension between the successful handoff and remembering the lease, even before the grant write.
        val resource = WorkflowHelperResource(grant, lease)
        live.update { old ->
            check(grant.reservation !in old) { "Workflow helper has concurrent owners" }
            old + (grant.reservation to resource)
        }
        return resource
    }

    /** Called after producer writes have joined. Unconfirmed release retains both capacity and proof. */
    suspend fun release(resource: WorkflowHelperResource): Boolean {
        if (!resource.isReleased) {
            resource.markClosing()
            // Join/reconcile the producer's last binding proposal before deleting its proof.
            journal.granted(resource.grant.copy(helper = null))
            resource.grant.helper?.let { journal.bind(resource.grant.reservation, it) }
            if (resource.lease.release() != HelperReleaseResult.Released) return false
            resource.markReleased()
        }
        journal.settled(resource.grant)
        live.update { it - resource.grant.reservation }
        return true
    }
}

/** Mutable only by its owning step or a later fenced cleanup owner; never shared across active drivers. */
internal class WorkflowHelperResource(initial: WorkflowHelperGrant, val lease: HelperLease) {
    private val snapshot = MutableStateFlow(initial)
    private val released = MutableStateFlow(false)
    private val closing = MutableStateFlow(false)
    val grant: WorkflowHelperGrant get() = snapshot.value
    val isReleased: Boolean get() = released.value
    val isClosing: Boolean get() = closing.value

    fun bind(helper: HelperId) {
        check(grant.helper == null || grant.helper == helper)
        snapshot.value = grant.copy(helper = helper)
    }

    fun markReleased() {
        released.value = true
    }

    fun markClosing() {
        closing.value = true
    }
}
