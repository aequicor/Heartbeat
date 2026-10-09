package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
internal class WorkflowRunExecution(
    private val storage: HarnessRunStorage,
    private val code: WorkflowCodeExecutor,
    private val ports: WorkflowHelperPorts,
    private val access: WorkflowRunAccess,
    private val clock: Clock,
    private val feedback: suspend (HarnessRunsIntent.Internal) -> Unit,
) {
    private val log = Log.tag("HarnessWorkflow")
    private val resources = ports.resources
    private val helpers = ports.helpers
    private val bindings = ports.bindings

    /** Revoked author work has joined. Stop native work but preserve Running replay state and its requests. */
    suspend fun pause(requested: WorkflowRun) {
        val run = claim(requested)
        if (run.status != WorkflowStatus.Running) {
            terminal(run, requested.driverGeneration)
        } else if (run.cancellation != null) {
            val progress = WorkflowRunJournal(run, storage) { feedback(it.feedback(run)) }
            finish(progress, WorkflowStatus.Failed(checkNotNull(run.cancellation)), requested.driverGeneration)
        } else {
            cleanup(run, onlyTerminalSteps = false)
        }
    }

    suspend fun execute(requested: WorkflowRun, isCurrent: () -> Boolean) {
        val run = claim(requested)
        if (run.status != WorkflowStatus.Running) {
            terminal(run, requested.driverGeneration)
            return
        }
        val progress = WorkflowRunJournal(run, storage) { feedback(it.feedback(run)) }
        val cancellation = run.cancellation
        if (cancellation != null) {
            finish(progress, WorkflowStatus.Failed(cancellation), requested.driverGeneration)
            return
        }
        while (!access.isAdmitted(run)) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw WorkflowExecutionUnavailable()
            if (clock.now() >= run.deadline) {
                fail(progress, WorkflowFailure.Timeout, requested.driverGeneration)
                return
            }
            delay(RETRY_INTERVAL)
        }
        cleanup(run, onlyTerminalSteps = true)
        val origin = access.origin(run)
        val routing = run.routing
        if (origin == null || origin.isHookRestricted || routing == null) {
            fail(progress, WorkflowFailure.Error, requested.driverGeneration)
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
        )
        val outcome = try {
            WorkflowStatus.Completed(code.execute(run, progress, agents, origin))
        } catch (error: WorkflowStepFailed) {
            // Typed author failure is journaled; exception text and cause never enter storage or logs.
            log.w(error) { "Workflow ended with a typed failure" }
            progress.replace { it.copy(cancellation = error.reason) }
            WorkflowStatus.Failed(error.reason)
        }
        if (!isCurrent()) throw WorkflowExecutionUnavailable()
        finish(progress, outcome, requested.driverGeneration)
    }

    /** Called during Restore as well, repairing the commit-to-outbox window without re-executing code. */
    suspend fun publish(run: WorkflowRun) {
        val payload = when (val status = run.status) {
            is WorkflowStatus.Completed -> "Workflow completed:\n" + status.result.toString()
            is WorkflowStatus.Failed -> "Workflow failed: ${status.reason}"
            WorkflowStatus.Running -> return
        }
        val bounded = if (payload.length <= SchedulerLimits.MAX_PAYLOAD) {
            payload
        } else {
            payload.take(SchedulerLimits.MAX_PAYLOAD - RESULT_SUFFIX.length) + RESULT_SUFFIX
        }
        helpers.finish(run.id.action, bounded)
    }

    /** Serial generation checkpoints bridge suspension/resume fences without dropping a parallel branch. */
    private suspend fun claim(requested: WorkflowRun): WorkflowRun {
        var stored: WorkflowRun = storage.load().singleOrNull { it.id == requested.id } ?: run {
            check(requested.steps.isEmpty() && requested.attempt == 0) { "Workflow journal is missing" }
            // A start can be suspended before its first effect runs; its immutable admitted input remains valid.
            val initial = requested.copy(driverGeneration = 0, attempt = 0, steps = emptyList(), awaiting = emptyMap())
            commit(initial, null)
        }
        if (stored.status != WorkflowStatus.Running) return stored
        if (stored.driverGeneration > requested.driverGeneration) throw WorkflowExecutionUnavailable()
        while (stored.driverGeneration < requested.driverGeneration) {
            val proposed = stored.copy(
                driverGeneration = stored.driverGeneration + 1,
                attempt = maxOf(stored.attempt, requested.attempt),
                cancellation = stored.cancellation ?: requested.cancellation,
                awaiting = emptyMap(),
            )
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
        val committed = progress.replace {
            it.copy(status = status, finishedAt = maxOf(it.startedAt, clock.now()), awaiting = emptyMap())
        }
        terminal(committed, projection)
    }

    private suspend fun terminal(run: WorkflowRun, projection: Long) {
        publish(run)
        feedback(HarnessRunsIntent.Internal.TerminalRestored(run, projection))
    }

    /** Release can remain unconfirmed indefinitely. No timeout or coroutine cancellation means native success. */
    private suspend fun cleanup(run: WorkflowRun, onlyTerminalSteps: Boolean) {
        val records = resources.records(run.id).associateBy { it.key }.toMutableMap()
        if (!onlyTerminalSteps) {
            for (step in run.steps.filter { it.helper != null && it.key !in records && !it.isTerminal }) {
                val binding = checkNotNull(bindings.lookup(checkNotNull(step.helper))) { "Missing helper ownership" }
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
                val resource = resources.acquire(grant, step.helper)
                resource.bind(checkNotNull(step.helper))
                records[step.key] = resource.grant
            }
        }
        for (record in records.values) {
            if (onlyTerminalSteps && run.steps.singleOrNull { it.key == record.key }?.isTerminal != true) continue
            val resource = resources.acquire(record)
            while (!resources.release(resource)) delay(RETRY_INTERVAL)
        }
    }
}

private fun WorkflowStep.feedback(run: WorkflowRun): HarnessRunsIntent.Internal = when (phase) {
    StepPhase.Prepared, StepPhase.Running -> HarnessRunsIntent.Internal.StepStarted(run.id, run.driverGeneration, this)
    StepPhase.Completed -> HarnessRunsIntent.Internal.StepFinished(run.id, run.driverGeneration, this)
    StepPhase.Failed -> HarnessRunsIntent.Internal.StepFailed(run.id, run.driverGeneration, this)
}

private val WorkflowStep.isTerminal: Boolean get() = phase == StepPhase.Completed || phase == StepPhase.Failed
private val RETRY_INTERVAL = 250.milliseconds
private const val RESULT_SUFFIX = "\n[Result shortened; read the workflow status for the complete value.]"
