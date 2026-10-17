package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import kotlinx.serialization.Serializable

/**
 * Granted organism capacity, saved before the organism is conceived and kept until its work has actually ended.
 * The grant is both the slot of the organism quotas and the proof of the one organism the step may own, so a
 * restart finds the organism again instead of conceiving a second one. No goal text is kept here. Records never
 * expire; only a confirmed end permits deleting one. A step has one grant and an organism id belongs to one step.
 */
@Serializable
internal data class WorkflowOrganismGrant(
    val run: RunId,
    val harness: HarnessId,
    val key: StepKey,
    val digest: String,
    val parent: SessionRef?,
    val organism: WorkflowOrganism,
) {
    init {
        require(Regex("[a-f0-9]{64}").matches(digest)) { "Invalid workflow step digest" }
    }

    override fun toString(): String = "WorkflowOrganismGrant(run=$run, key=$key)"
}

/** Whether the organism quotas had room for a grant; a refusal changes nothing. */
internal enum class OrganismAdmission {
    /** The grant is saved, or was already saved exactly. */
    Granted,

    /** The run already owns [HarnessLimits.ORGANISMS_PER_RUN] organisms. */
    RunLimit,

    /** The profile already owns [HarnessLimits.ORGANISMS_PER_PROFILE] organisms. */
    ProfileLimit,
}

/**
 * Durable organism grants of the profile. Admission and the write are one atomic update, so concurrent runs cannot
 * both take the last slot. A grant restored after a restart is recognized exactly and never queued again.
 */
internal interface WorkflowOrganismJournal {
    /** Saves [grant] when the quotas have room, or recognizes the same grant already saved. Conflicts throw. */
    suspend fun acquire(grant: WorkflowOrganismGrant): OrganismAdmission

    /** Every grant whose organism may still be working. */
    suspend fun pending(): List<WorkflowOrganismGrant>

    /** Exact removal after the organism's work has ended. A stale or conflicting record cannot settle a newer one. */
    suspend fun settled(grant: WorkflowOrganismGrant)
}

/** The quota decision for [grant] over the [records] already granted; a pure rule shared by every journal. */
internal fun admissionFor(records: List<WorkflowOrganismGrant>, grant: WorkflowOrganismGrant): OrganismAdmission =
    when {
        records.count { it.run == grant.run } >= HarnessLimits.ORGANISMS_PER_RUN -> OrganismAdmission.RunLimit
        records.size >= HarnessLimits.ORGANISMS_PER_PROFILE -> OrganismAdmission.ProfileLimit
        else -> OrganismAdmission.Granted
    }

/**
 * The organism a step owns, derived from its run and structural key alone. Replay and recovery therefore compute
 * the same identity without any stored state, and the organic AI conception of an existing id is ignored.
 */
internal fun workflowOrganism(run: RunId, key: StepKey, digest: (String) -> String): WorkflowOrganism =
    WorkflowOrganism(ORGANISM_PREFIX + digest("organism|${run.value}|${key.value}").take(ORGANISM_HASH_CHARS))

private const val ORGANISM_PREFIX = "wf-"
private const val ORGANISM_HASH_CHARS = 40
