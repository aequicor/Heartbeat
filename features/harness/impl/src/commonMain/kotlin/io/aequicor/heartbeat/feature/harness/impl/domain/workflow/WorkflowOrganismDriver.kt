package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismRefusal
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One generation's organism lane. The organism of a step is derived from its run and key, its grant and its binding
 * are saved before anything is conceived, and a repeated step reconciles with that organism instead of conceiving
 * another: Running is saved before asking for conception, so an uncertain attempt is never repeated. A restored
 * Prepared step may come from an older host that saved Running after conception; unavailability cannot refuse it.
 * Only this lane addresses the organism. When its call ends or is cancelled, the quota is
 * returned only after organic AI shows the organism's work ended, which for an abort means its sessions were
 * released; a revoked driver leaves the organism running for the next owner, which reconciles it. The business
 * meaning of an answer is the workflow's to judge.
 */
internal class WorkflowOrganismDriver(
    private val run: WorkflowRun,
    private val progress: WorkflowRunJournal,
    private val ports: WorkflowOrganismPorts,
    private val workspace: WorkspaceRef?,
    private val isAdmitted: suspend () -> Boolean,
    private val driver: Job? = null,
) : WorkflowOrganismSteps {
    private val log = Log.tag("HarnessWorkflow")
    private val resources = ports.resources
    private val host = ports.host

    override suspend fun execute(
        key: StepKey,
        digest: String,
        goal: String,
        options: OrganismOptions,
    ): OrganismOutcome = guarded {
        val owner = currentCoroutineContext()[Job]
        admitted()
        val organism = workflowOrganism(run.id, key, ports.digest)
        val step = progress.steps.singleOrNull { it.key == key }
        if (step != null && (step.promptSha != digest || step.organism != organism)) {
            throw WorkflowStepFailed(WorkflowFailure.Diverged)
        }
        val grant = WorkflowOrganismGrant(run.id, run.harness, key, digest, run.caller, organism)
        awaitSlot(grant)
        val current = step ?: WorkflowStep(key, digest, organism = organism).also { progress.record(it) }
        try {
            conclude(current, organism, goal, options, canRefuseUnavailable = step == null)
        } finally {
            withContext(NonCancellable) { settle(grant, owner) }
        }
    }

    /** Waits for the run's and the profile's organism quota; waiting is cancellable and never a step failure. */
    private suspend fun awaitSlot(grant: WorkflowOrganismGrant) {
        var isReported = false
        var releasePolls = 0
        while (resources.acquire(grant) != OrganismAdmission.Granted) {
            if (!isReported) log.d { "Workflow organism waits for capacity" }
            isReported = true
            resources.sweepEnded(run.id, retryUnconfirmed = releasePolls++ % ABORT_RETRY_POLLS == 0)
            delay(ORGANISM_POLL_INTERVAL)
            admitted()
        }
    }

    /** Observes the organism until it has an outcome; records the attempt before asking the host to conceive. */
    private suspend fun conclude(
        first: WorkflowStep,
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
        canRefuseUnavailable: Boolean,
    ): OrganismOutcome {
        var step = first
        var unavailablePolls = 0
        var answerPolls = 0
        while (true) {
            admitted()
            val outcome = when (val view = host.view(organism)) {
                OrganismView.Unavailable -> if (canRefuseUnavailable) refusalAfter(step, ++unavailablePolls) else null

                OrganismView.Absent -> if (step.phase == StepPhase.Prepared) {
                    step = running(step)
                    conceive(organism, goal, options)
                } else {
                    // A conception may have been sent: absence cannot authorize another attempt.
                    OrganismOutcome.Ended(OrganismEnd.Failed)
                }

                OrganismView.Developing -> {
                    step = running(step)
                    null
                }

                // The quota is returned only after the abort in the lane's release confirms the end.
                is OrganismView.Stalled -> OrganismOutcome.Ended(view.end)

                is OrganismView.Answered -> answer(view, organism) { answerPolls++ % ABORT_RETRY_POLLS == 0 }

                // Aborted from outside: the step's outcome is known now, the lane's release waits for the confirmation.
                OrganismView.Aborting, OrganismView.AbortUnconfirmed, OrganismView.Aborted ->
                    OrganismOutcome.Ended(OrganismEnd.Aborted)
            }
            if (outcome != null) return finish(step, outcome)
            delay(ORGANISM_POLL_INTERVAL)
        }
    }

    private suspend fun conceive(
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
    ): OrganismOutcome? {
        admitted()
        val context = run.routing?.organismContext
        val conceived = when {
            context != null -> host.conceiveIsolated(organism, goal, options, workspace, context)
            options.execution != null -> OrganismConception.Rejected
            else -> host.conceive(organism, goal, options, workspace)
        }
        return when (conceived) {
            OrganismConception.Accepted -> null
            OrganismConception.Unavailable -> OrganismOutcome.Refused(OrganismRefusal.Unavailable)
            OrganismConception.Rejected -> OrganismOutcome.Refused(OrganismRefusal.Rejected)
        }
    }

    private suspend fun answer(
        view: OrganismView.Answered,
        organism: WorkflowOrganism,
        shouldRetry: () -> Boolean,
    ): OrganismOutcome? = when (view.release) {
        OrganismRelease.Confirmed -> if (run.routing?.organismContext != null) {
            val output = host.output(organism)
            if (output == null) {
                OrganismOutcome.Ended(OrganismEnd.Failed)
            } else {
                OrganismOutcome.Answered(view.text, output)
            }
        } else {
            OrganismOutcome.Answered(view.text)
        }

        OrganismRelease.Pending -> null

        OrganismRelease.Unconfirmed -> {
            if (shouldRetry()) {
                admitted()
                host.abort(organism)
            }
            null
        }
    }

    /**
     * Organic AI that stays unavailable refuses an organism that was never conceived, once it had time to awaken. An
     * organism whose conception may have been sent keeps waiting: the run's own deadline bounds the wait.
     */
    private fun refusalAfter(step: WorkflowStep, polls: Int): OrganismOutcome? =
        OrganismOutcome.Refused(OrganismRefusal.Unavailable)
            .takeIf { step.phase == StepPhase.Prepared && polls > ORGANISM_UNAVAILABLE_POLLS }

    private suspend fun running(step: WorkflowStep): WorkflowStep = if (step.phase == StepPhase.Prepared) {
        step.copy(phase = StepPhase.Running).also { progress.record(it) }
    } else {
        step
    }

    /** Saves the exact outcome; an answer beyond the result bound fails the step instead of being cut. */
    private suspend fun finish(step: WorkflowStep, outcome: OrganismOutcome): OrganismOutcome {
        val value = outcome.toJournal()
        if (value == null) {
            progress.fail(step.key, WorkflowFailure.Error)
            throw WorkflowStepFailed(WorkflowFailure.Error)
        }
        progress.record(step.copy(phase = StepPhase.Completed, result = value, failure = null))
        log.d { "Workflow organism step concluded" }
        return outcome
    }

    /**
     * Returns the slot of a terminal step, or of one a failing sibling cancelled while its driver generation still
     * owns the run: that organism is stopped now, or a retried group would wait for its quota until the deadline. A
     * cancelled [owner] tries once; cleanup of the run finishes the rest. A revoked driver keeps the slot and the
     * organism for the next owner.
     */
    private suspend fun settle(grant: WorkflowOrganismGrant, owner: Job?) {
        val step = progress.steps.singleOrNull { it.key == grant.key }
        val isTerminal = step?.phase == StepPhase.Completed || step?.phase == StepPhase.Failed
        val isAbandoned = !isTerminal && owner?.isCancelled == true && driver?.isActive == true && isStillAdmitted()
        if (isAbandoned) log.d { "Workflow branch cancelled by its group; stopping its organism" }
        if (isTerminal || isAbandoned) resources.release(grant, step) { owner?.isActive != false }
    }

    private suspend fun isStillAdmitted(): Boolean = try {
        isAdmitted()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Workflow admission unavailable while settling a branch" }
        false
    }

    private suspend fun admitted() {
        currentCoroutineContext().ensureActive()
        if (!isAdmitted()) throw WorkflowExecutionUnavailable()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: WorkflowStepFailed) {
        throw error
    } catch (error: Exception) {
        log.w(harnessScriptFailure(error)) { "Workflow organism operation awaits reconciliation" }
        throw WorkflowExecutionUnavailable()
    }
}
