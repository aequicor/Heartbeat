package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRouting
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.api.workflow.withoutLiveRequests
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** Live admission and immutable origin decoding supplied by the profile adapter, with no author callbacks. */
internal interface WorkflowRunAccess {
    suspend fun isAdmitted(run: WorkflowRun): Boolean
    fun origin(run: WorkflowRun): HarnessCallOrigin?
}

/**
 * Executes one fenced request. The coordinator joins the prior owner first; this class then claims storage,
 * replays, drains helpers, saves terminal state and publishes the durable scheduler outbox in that order.
 * Any uncertain infrastructure exception leaves the same operation retryable; no new prompt is inferred from
 * a lost acknowledgement. Terminal records are authoritative even if their machine feedback was interrupted.
 */
// Independent ports stay explicit at this coordinator boundary; helper and organism ports are already grouped.
@Suppress("LongParameterList")
internal class WorkflowRunExecution(
    private val storage: HarnessRunStorage,
    private val code: WorkflowCodeExecutor,
    private val ports: WorkflowHelperPorts,
    private val organisms: WorkflowOrganismPorts,
    private val access: WorkflowRunAccess,
    private val clock: Clock,
    private val feedback: suspend (HarnessRunsIntent.Internal) -> Unit,
) {
    private val log = Log.tag("HarnessWorkflow")
    private val resources = ports.resources
    private val helpers = ports.helpers
    private val bindings = ports.bindings

    /** Results delivered to the outbox whose durable mark is not yet confirmed; cleared once the mark commits. */
    private val delivered = MutableStateFlow<Set<RunId>>(emptySet())

    /** The organism requests of every run whose driver generation is executing now, for the user's answers. */
    private val inboxes = MutableStateFlow<Map<RunId, OrganismInbox>>(emptyMap())

    /**
     * Revoked author work has joined. Stop native work but preserve Running replay state and its requests.
     * Returns true when the run is terminal, so its driver owner can be retired.
     */
    suspend fun pause(requested: WorkflowRun): Boolean {
        val run = claim(requested)
        if (run.status != WorkflowStatus.Running) {
            terminal(run, requested.driverGeneration)
        } else if (run.cancellation != null) {
            val progress = WorkflowRunJournal(run, storage) { feedback(it.feedback(run)) }
            finish(progress, WorkflowStatus.Failed(checkNotNull(run.cancellation)), requested.driverGeneration)
        } else {
            cleanup(run, onlyTerminalSteps = false)
            return false
        }
        return true
    }

    /** Returns only after a terminal record is committed and published; failures leave the request retryable. */
    suspend fun execute(requested: WorkflowRun, isCurrent: () -> Boolean): Boolean {
        val run = claim(requested)
        if (run.status != WorkflowStatus.Running) {
            terminal(run, requested.driverGeneration)
        } else {
            drive(run, requested.driverGeneration, isCurrent)
        }
        return true
    }

    private suspend fun drive(run: WorkflowRun, projection: Long, isCurrent: () -> Boolean) {
        val progress = WorkflowRunJournal(run, storage) { feedback(it.feedback(run)) }
        val cancellation = run.cancellation
        if (cancellation != null) {
            finish(progress, WorkflowStatus.Failed(cancellation), projection)
            return
        }
        while (!access.isAdmitted(run)) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw WorkflowExecutionUnavailable()
            if (clock.now() >= run.deadline) {
                fail(progress, WorkflowFailure.Timeout, projection)
                return
            }
            delay(RETRY_INTERVAL)
        }
        cleanup(run, onlyTerminalSteps = true)
        releaseOrganisms(run, onlyTerminalSteps = true)
        val origin = access.origin(run)
        val routing = run.routing
        if (origin == null || origin.isHookRestricted || routing == null) {
            fail(progress, WorkflowFailure.Error, projection)
            return
        }
        val permissions = WorkflowPermissions {
            feedback(HarnessRunsIntent.Internal.PermissionsChanged(run.id, run.driverGeneration, it))
        }
        val agents = WorkflowHelperDriver(
            run,
            progress,
            ports,
            routing.workspace,
            routing.handoff,
            { isCurrent() && access.isAdmitted(run) },
            permissions::update,
            driver = currentCoroutineContext()[Job],
        )
        val requests = WorkflowOrganismRequests {
            feedback(HarnessRunsIntent.Internal.OrganismPermissionsChanged(run.id, run.driverGeneration, it))
        }
        val organismSteps = organismLane(run, progress, routing, requests, isCurrent)
        val outcome = withAnswers(run, requests) {
            try {
                WorkflowStatus.Completed(code.execute(run, progress, agents, organismSteps, origin))
            } catch (error: WorkflowStepFailed) {
                // Typed author failure is journaled; exception text and cause never enter storage or logs.
                log.w(error) { "Workflow ended with a typed failure" }
                progress.replace { it.copy(cancellation = error.reason) }
                WorkflowStatus.Failed(error.reason)
            }
        }
        if (!isCurrent()) throw WorkflowExecutionUnavailable()
        finish(progress, outcome, projection)
    }

    private suspend fun organismLane(
        run: WorkflowRun,
        progress: WorkflowRunJournal,
        routing: WorkflowRouting,
        requests: WorkflowOrganismRequests,
        isCurrent: () -> Boolean,
    ) = WorkflowOrganismDriver(
        run,
        progress,
        organisms,
        routing.workspace,
        { isCurrent() && access.isAdmitted(run) },
        driver = currentCoroutineContext()[Job],
        requests = requests,
    )

    /**
     * Hands the user's answer to the lane of [step] in the driver generation that showed the request. An answer for
     * any other generation, a run no driver executes or a request that is no longer shown is dropped: the user sees
     * the request again if it still asks. Only the lane that owns the organism sends the answer to it.
     */
    suspend fun answerOrganism(
        run: RunId,
        generation: Long,
        step: StepKey,
        cell: String,
        decision: PermissionDecision,
    ) {
        val inbox = inboxes.value[run]?.takeIf { it.generation == generation }
        if (inbox == null || !inbox.requests.answer(step, cell, decision)) {
            log.d { "Workflow organism answer has no live request" }
        }
    }

    private suspend fun <T> withAnswers(
        run: WorkflowRun,
        requests: WorkflowOrganismRequests,
        block: suspend () -> T,
    ): T {
        val inbox = OrganismInbox(run.driverGeneration, requests)
        inboxes.update { it + (run.id to inbox) }
        log.v { "Workflow organism answers open" }
        try {
            return block()
        } finally {
            inboxes.update { if (it[run.id] === inbox) it - run.id else it }
            log.v { "Workflow organism answers closed" }
        }
    }

    /**
     * Called during Restore as well, repairing the commit-to-outbox window without re-executing code. The outbox
     * write is not idempotent (it republishes the finished event), so a durable mark skips delivered results; a
     * crash between delivery and the mark repeats the delivery at most once.
     */
    suspend fun publish(run: WorkflowRun) {
        val payload = when (val status = run.status) {
            is WorkflowStatus.Completed -> "Workflow completed:\n" + status.result.toString()
            is WorkflowStatus.Failed -> "Workflow failed: ${status.reason}"
            WorkflowStatus.Running -> return
        }
        if (storage.isPublished(run.id)) {
            delivered.update { it - run.id }
            return
        }
        // An uncertain mark retries only the mark: this owner already delivered the result to the outbox.
        if (run.id !in delivered.value) {
            val bounded = if (payload.length <= SchedulerLimits.MAX_PAYLOAD) {
                payload
            } else {
                payload.take(SchedulerLimits.MAX_PAYLOAD - RESULT_SUFFIX.length) + RESULT_SUFFIX
            }
            helpers.finish(run.id.action, bounded)
            delivered.update { it + run.id }
        }
        storage.markPublished(run.id)
        delivered.update { it - run.id }
        log.d { "Workflow result published" }
    }

    /** Serial generation checkpoints bridge suspension/resume fences without dropping a parallel branch. */
    private suspend fun claim(requested: WorkflowRun): WorkflowRun {
        var stored: WorkflowRun = storage.find(requested.id) ?: run {
            check(requested.steps.isEmpty() && requested.attempt == 0) { "Workflow journal is missing" }
            // A start can be suspended before its first effect runs; its immutable admitted input remains valid.
            val initial = requested.copy(driverGeneration = 0, attempt = 0, steps = emptyList()).withoutLiveRequests()
            commit(initial, null)
        }
        if (stored.status != WorkflowStatus.Running) return stored
        if (stored.driverGeneration > requested.driverGeneration) throw WorkflowExecutionUnavailable()
        while (stored.driverGeneration < requested.driverGeneration) {
            val proposed = stored.copy(
                driverGeneration = stored.driverGeneration + 1,
                attempt = maxOf(stored.attempt, requested.attempt),
                cancellation = stored.cancellation ?: requested.cancellation,
            ).withoutLiveRequests()
            stored = commit(proposed, stored.driverGeneration)
        }
        if (stored.cancellation == null && requested.cancellation != null) {
            val proposed = stored.copy(cancellation = requested.cancellation)
            stored = commit(proposed, stored.driverGeneration)
        }
        return stored
    }

    private suspend fun commit(proposed: WorkflowRun, expected: Long?): WorkflowRun {
        if (!storage.save(proposed, expected)) throw WorkflowExecutionUnavailable()
        return proposed
    }

    private suspend fun fail(progress: WorkflowRunJournal, reason: WorkflowFailure, projection: Long) {
        progress.replace { it.copy(cancellation = reason) }
        finish(progress, WorkflowStatus.Failed(reason), projection)
    }

    private suspend fun finish(progress: WorkflowRunJournal, status: WorkflowStatus, projection: Long) {
        cleanup(progress.state.value, onlyTerminalSteps = false)
        releaseOrganisms(progress.state.value, onlyTerminalSteps = false)
        val committed = progress.replace {
            it.copy(status = status, finishedAt = maxOf(it.startedAt, clock.now())).withoutLiveRequests()
        }
        terminal(committed, projection)
    }

    private suspend fun terminal(run: WorkflowRun, projection: Long) {
        publish(run)
        feedback(HarnessRunsIntent.Internal.TerminalRestored(run, projection))
    }

    /**
     * Release can remain unconfirmed indefinitely. No timeout or coroutine cancellation means native success.
     * Leases this run already holds are released before any new slot is claimed: acquiring first could wait for
     * this owner's own capacity forever. Steps without a live record then take one slot at a time.
     */
    private suspend fun cleanup(run: WorkflowRun, onlyTerminalSteps: Boolean) {
        val records = resources.records(run.id)
        for (record in records) {
            if (onlyTerminalSteps && run.steps.singleOrNull { it.key == record.key }?.isTerminal != true) continue
            settle(resources.acquire(record))
        }
        if (onlyTerminalSteps) return
        val covered = records.mapTo(mutableSetOf()) { it.key }
        for (step in run.steps.filter { it.helper != null && it.key !in covered && !it.isTerminal }) {
            val helper = checkNotNull(step.helper)
            val binding = bindings.lookup(helper)
            if (binding == null) {
                // An expired proof cannot release the helper; retrying would keep this run Running forever.
                log.w { "Skip cleanup of a workflow helper whose ownership proof expired" }
                continue
            }
            check(binding.owner == run.id.action && binding.harness == run.harness)
            val grant = WorkflowHelperGrant(
                ActionId("wf_slot_" + Uuid.random().toHexString()),
                run.id,
                run.harness,
                step.key,
                step.promptSha,
                run.caller,
                checkNotNull(step.request),
                binding.attachRequest,
            )
            val resource = resources.acquire(grant, helper)
            resource.bind(helper)
            settle(resource)
        }
    }

    private suspend fun settle(resource: WorkflowHelperResource) {
        while (!resources.release(resource)) delay(RETRY_INTERVAL)
    }

    /**
     * Returns the organism slots of [run]. An unfinished run is never paused by aborting its organisms: organic AI
     * keeps them under its own lifecycle and a resumed run reconciles the same ones. A terminal run, a cancelled one
     * included, aborts every organism it still owns and waits until organic AI shows their work ended. Nothing else
     * releases a slot, so an organism that cannot be confirmed ended keeps its quota and its run open.
     */
    private suspend fun releaseOrganisms(run: WorkflowRun, onlyTerminalSteps: Boolean) {
        for (grant in organisms.resources.records(run.id)) {
            val step = run.steps.singleOrNull { it.key == grant.key }
            if (onlyTerminalSteps && step?.isTerminal != true) continue
            organisms.resources.release(grant, step) { true }
        }
    }
}

private data class OrganismInbox(val generation: Long, val requests: WorkflowOrganismRequests)

private fun WorkflowStep.feedback(run: WorkflowRun): HarnessRunsIntent.Internal = when (phase) {
    StepPhase.Prepared, StepPhase.Running -> HarnessRunsIntent.Internal.StepStarted(run.id, run.driverGeneration, this)
    StepPhase.Completed -> HarnessRunsIntent.Internal.StepFinished(run.id, run.driverGeneration, this)
    StepPhase.Failed -> HarnessRunsIntent.Internal.StepFailed(run.id, run.driverGeneration, this)
}

private val WorkflowStep.isTerminal: Boolean get() = phase == StepPhase.Completed || phase == StepPhase.Failed
private val RETRY_INTERVAL = 250.milliseconds
private const val RESULT_SUFFIX = "\n[Result shortened; read the workflow status for the complete value.]"
