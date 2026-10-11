package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.Serializable

/**
 * Granted shared capacity, saved before creating a chat. The initial request reconstructs a Prepared step if
 * creation committed before the run journal did. Subsequent attempts live only in the run journal. No text is
 * kept here. Records never expire; only confirmed lease release permits deletion. A reservation is never reused.
 */
@Serializable
internal data class WorkflowHelperGrant(
    val reservation: ActionId,
    val run: RunId,
    val harness: HarnessId,
    val key: StepKey,
    val digest: String,
    val parent: SessionRef?,
    val request: RequestId,
    val attachRequest: RequestId,
    val helper: HelperId? = null,
) {
    init {
        require(Regex("[a-f0-9]{64}").matches(digest)) { "Invalid workflow step digest" }
        require(request.value.isNotBlank() && attachRequest.value.isNotBlank()) { "Missing request identity" }
    }

    override fun toString(): String = "WorkflowHelperGrant(run=$run, key=$key, hasHelper=${helper != null})"
}

/** Producer writes after acquire and before create; strict recovery reads while old producers are fenced. */
internal interface WorkflowHelperJournal {
    /** Exact retry cannot erase a bound helper. There is at most one pending grant per run/step. */
    suspend fun granted(grant: WorkflowHelperGrant)
    suspend fun bind(reservation: ActionId, helper: HelperId): WorkflowHelperGrant
    suspend fun pending(): List<WorkflowHelperGrant>

    /** Exact record removal after release. A stale or conflicting record cannot settle newer ownership. */
    suspend fun settled(grant: WorkflowHelperGrant)
}
