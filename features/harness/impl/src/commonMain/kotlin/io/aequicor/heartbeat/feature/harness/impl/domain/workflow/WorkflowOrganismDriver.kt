package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
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
 * another: conception is repeated only while the step is still Prepared, so an organism that was once seen alive
 * is never replaced. Only this lane addresses the organism. When its call ends or is cancelled, the quota is
 * returned only after organic AI shows the organism's work ended, which for an abort means its sessions were
 * released; a revoked driver leaves the organism running for the next owner, which reconciles it. The business
 * meaning of an answer is the workflow's to judge.
 *
 * An organism has no chat, so its cells ask the user through the runs view: this lane shows what they ask and, being
 * the only one that addresses the organism, sends each of the user's answers to the cell that asked. Neither the
 * organism nor the requests' texts ever reach the workflow's author, the log or another session.
 */
internal class WorkflowOrganismDriver(
    private val run: WorkflowRun,
    private val progress: WorkflowRunJournal,
    private val ports: WorkflowOrganismPorts,
    private val workspace: WorkspaceRef?,
    private val isAdmitted: suspend () -> Boolean,
    private val driver: Job? = null,
    private val requests: WorkflowOrganismRequests = WorkflowOrganismRequests {},
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
            conclude(current, organism, goal, options)
        } finally {
            withContext(NonCancellable) {
                hide(key)
                settle(grant, owner)
            }
        }
    }

    /** Waits for the run's and the profile's organism quota; waiting is cancellable and never a step failure. */
    private suspend fun awaitSlot(grant: WorkflowOrganismGrant) {
        var isReported = false
        while (resources.acquire(grant) != OrganismAdmission.Granted) {
            if (!isReported) log.d { "Workflow organism waits for capacity" }
            isReported = true
            delay(ORGANISM_POLL_INTERVAL)
            admitted()
        }
    }

    /** Observes the organism until it has an outcome, conceiving it first only if it was never seen alive. */
    private suspend fun conclude(
        first: WorkflowStep,
        organism: WorkflowOrganism,
        goal: String,
        options: OrganismOptions,
    ): OrganismOutcome {
        var step = first
        var unavailablePolls = 0
        val answered = mutableMapOf<AskedRequest, Int>()
        while (true) {
            admitted()
            val view = host.view(organism)
            if (view !is OrganismView.Developing) {
                answered.clear()
                requests.show(step.key, emptyList())
            }
            val outcome = when (view) {
                OrganismView.Unavailable -> refusalAfter(step, ++unavailablePolls)

                OrganismView.Absent -> if (step.phase == StepPhase.Prepared) {
                    admitted()
                    when (host.conceive(organism, goal, options, workspace)) {
                        OrganismConception.Accepted -> null
                        OrganismConception.Unavailable -> refusalAfter(step, ++unavailablePolls)
                        OrganismConception.Rejected -> OrganismOutcome.Refused(OrganismRefusal.Rejected)
                    }
                } else {
                    // Seen alive before and gone now: its end cannot be confirmed, and it is never replaced.
                    OrganismOutcome.Ended(OrganismEnd.Failed)
                }

                is OrganismView.Developing -> {
                    step = running(step)
                    relay(organism, step.key, view.requests, answered)
                    null
                }

                // The quota is returned only after the abort in the lane's release confirms the end.
                is OrganismView.Stalled -> OrganismOutcome.Ended(view.end)

                is OrganismView.Answered -> OrganismOutcome.Answered(view.text)

                // Aborted from outside: the step's outcome is known now, the lane's release waits for the confirmation.
                OrganismView.Aborting, OrganismView.AbortUnconfirmed, OrganismView.Aborted ->
                    OrganismOutcome.Ended(OrganismEnd.Aborted)
            }
            if (outcome != null) return finish(step, outcome)
            delay(ORGANISM_POLL_INTERVAL)
        }
    }

    /**
     * Gives the user's queued answers to the cells that asked and shows the rest. An answer reaches only a cell that
     * still asks for exactly that. An accepted intent is not a delivery receipt: its asynchronous Respond may fail.
     * Hide the request briefly against double clicks, then offer a still-live request again for an explicit user
     * retry. Never resend automatically. Unaccepted answers stay shown; disappeared requests lose suppression.
     */
    private suspend fun relay(
        organism: WorkflowOrganism,
        key: StepKey,
        asking: List<OrganismCellRequest>,
        answered: MutableMap<AskedRequest, Int>,
    ) {
        val live = asking.mapTo(mutableSetOf()) { it.asked() }
        val pending = answered.iterator()
        while (pending.hasNext()) {
            val entry = pending.next()
            if (entry.key !in live || entry.value <= 1) pending.remove() else entry.setValue(entry.value - 1)
        }
        for (answer in requests.take(key)) {
            val target = asking.firstOrNull { it.cell == answer.cell && it.request.accepts(answer.decision) }
            if (target == null || target.asked() in answered) continue
            admitted()
            if (host.decide(organism, answer.cell, answer.decision)) {
                answered[target.asked()] = ORGANISM_ANSWER_RETRY_POLLS
                log.i { "Workflow organism cell answer submitted" }
            }
        }
        val open = asking.filter { it.asked() !in answered }
        requests.show(key, open.map { OrganismPermission(key, it.cell, it.name, it.request) })
    }

    /** The user's view of the step is cleared with the lane; a failure to publish never hides the real outcome. */
    private suspend fun hide(key: StepKey) {
        try {
            requests.show(key, emptyList())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(harnessScriptFailure(error)) { "Workflow organism requests could not be cleared" }
        }
    }

    /**
     * Organic AI that stays unavailable refuses an organism that was never conceived, once it had time to awaken. An
     * organism already seen alive keeps waiting: the machine revives it, and the run's own deadline bounds the wait.
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

/** Five seconds of observed development; only suppresses clicks and never authorizes another response. */
internal const val ORGANISM_ANSWER_RETRY_POLLS = 20

/** A request as the lane tells it apart from others: the cell that asked and the turn's own request. */
private data class AskedRequest(val cell: String, val request: PermissionRequestId, val turn: TurnId)

private fun OrganismCellRequest.asked() = AskedRequest(cell, request.id, request.turn)
