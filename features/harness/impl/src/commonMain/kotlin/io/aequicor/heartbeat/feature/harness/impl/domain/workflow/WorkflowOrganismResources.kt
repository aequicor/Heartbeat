package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Organism-side ports of workflow execution: the organism resources, organic AI and the digest that derives an
 * organism's identity from its run and key.
 */
internal data class WorkflowOrganismPorts(
    val resources: WorkflowOrganismResources,
    val host: OrganismHost,
    val digest: (String) -> String,
)

/**
 * Profile-owned organism quota slots, each a durable grant that outlives a paused or cancelled driver. A slot is
 * returned only after organic AI shows that no cell of the organism can take another turn and its native work
 * stopped: an unconfirmed stop keeps both the quota and the proof, because an organism that is merely asleep revives
 * when organic AI awakens, and one that is merely marked aborted may still run its turns until the release reports.
 */
internal class WorkflowOrganismResources(
    private val journal: WorkflowOrganismJournal,
    private val host: OrganismHost,
) {
    private val log = Log.tag("HarnessWorkflow")

    /** The grants a run still owns, whether its steps are running or already ended. */
    suspend fun records(run: RunId): List<WorkflowOrganismGrant> =
        durable { journal.pending() }.filter { it.run == run }

    /** Takes the slot of [grant] or recognizes the one it already holds; a full quota is not a failure. */
    suspend fun acquire(grant: WorkflowOrganismGrant): OrganismAdmission = durable { journal.acquire(grant) }

    /**
     * Stops the organism of [grant] and returns its slot once it has ended; false while that is not confirmed and
     * [keepTrying] is false. [step] is the recorded step of the grant: its outcome can already prove the end, such as
     * an answer, and a refusal means nothing was ever conceived. An abort that was seen proves nothing, since it only
     * marks the organism. Anything else asks organic AI to abort and reads the result back: neither a command, a
     * mark nor a timeout proves that the work stopped, only the confirmed release of its sessions does. A release
     * that organic AI could not confirm is asked for again, but not at every poll.
     */
    suspend fun release(grant: WorkflowOrganismGrant, step: WorkflowStep?, keepTrying: () -> Boolean): Boolean {
        if (step.isKnownEnded()) {
            settle(grant)
            return true
        }
        var cooldown = 0
        while (true) {
            var view = host.view(grant.organism)
            if (cooldown > 0) cooldown--
            if (view.isAbortable && (view != OrganismView.AbortUnconfirmed || cooldown == 0)) {
                host.abort(grant.organism)
                if (view == OrganismView.AbortUnconfirmed) cooldown = ABORT_RETRY_POLLS
                view = host.view(grant.organism)
            }
            if (view.isEnded) {
                settle(grant)
                return true
            }
            if (!keepTrying()) return false
            delay(ORGANISM_POLL_INTERVAL)
        }
    }

    private suspend fun settle(grant: WorkflowOrganismGrant) {
        durable { journal.settled(grant) }
        log.d { "Workflow organism slot settled" }
    }

    /** Retries only an identical journal write, never an organic AI command. */
    private suspend fun <T> durable(block: suspend () -> T): T {
        var isReported = false
        while (true) {
            try {
                return block()
            } catch (error: HarnessStorageUncertain) {
                if (!isReported) log.w(error) { "Workflow organism capacity write awaits confirmation" }
                isReported = true
                delay(ORGANISM_POLL_INTERVAL)
            }
        }
    }
}

/**
 * An answered or refused step proves that no cell works for it. An abort that was seen does not: the organism is only
 * marked, and every other ending needs a read.
 */
private fun WorkflowStep?.isKnownEnded(): Boolean {
    val outcome = this?.takeIf { it.phase == StepPhase.Completed }?.result?.toOrganismOutcome()
    return outcome is OrganismOutcome.Answered || outcome is OrganismOutcome.Refused
}

internal val ORGANISM_POLL_INTERVAL = 250.milliseconds

/** Polls between two asks to release an abort that organic AI could not confirm: 10 seconds. */
internal const val ABORT_RETRY_POLLS = 40

/** Polls an organism that was never conceived waits for organic AI to awaken before it is refused: 30 seconds. */
internal const val ORGANISM_UNAVAILABLE_POLLS = 120
