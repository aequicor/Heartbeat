package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The user's answer to a request of the cell [cell]; its text is never rendered by [toString]. */
internal data class OrganismAnswer(val cell: String, val decision: PermissionDecision) {
    override fun toString(): String = "OrganismAnswer(***)"
}

/**
 * The live requests of the cells of the organisms of one run generation, and the user's answers on their way back.
 * Each step's owner shows what its organism's cells ask through [show]; the merged snapshot goes to [publish], which
 * is the runs view. An answer is queued only for a request that is shown right now, and only the owner of that step
 * takes it, so the answer is sent by the lane that owns the organism and by nobody else. Nothing here is stored: a
 * new generation starts with an empty set and its owners show their organisms' requests again. Texts are never logged.
 */
internal class WorkflowOrganismRequests(private val publish: suspend (List<OrganismPermission>) -> Unit) {
    private val lock = Mutex()
    private val shown = mutableMapOf<StepKey, List<OrganismPermission>>()
    private val answers = mutableMapOf<StepKey, List<OrganismAnswer>>()

    /** Replaces what [step] shows and publishes the merged snapshot if it changed; no requests drop stale answers. */
    suspend fun show(step: StepKey, requests: List<OrganismPermission>) = lock.withLock {
        if (requests.isEmpty()) answers.remove(step)
        if (shown[step].orEmpty() == requests) return@withLock
        if (requests.isEmpty()) shown.remove(step) else shown[step] = requests
        publish(shown.keys.sortedBy { it.value }.flatMap { shown.getValue(it) })
    }

    /** Queues the answer for the owner of [step]; false when [cell] is not asking for it, so nothing is queued. */
    suspend fun answer(step: StepKey, cell: String, decision: PermissionDecision): Boolean = lock.withLock {
        val isAsked = shown[step].orEmpty().any { it.cell == cell && it.request.accepts(decision) }
        if (isAsked) answers[step] = (answers[step].orEmpty() + OrganismAnswer(cell, decision)).takeLast(ANSWERS_QUEUED)
        isAsked
    }

    /** Hands the owner of [step] the answers queued for it, oldest first. */
    suspend fun take(step: StepKey): List<OrganismAnswer> = lock.withLock { answers.remove(step).orEmpty() }
}

private const val ANSWERS_QUEUED = 8
