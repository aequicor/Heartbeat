package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageException
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.milliseconds

/**
 * One generation's serialized writer, shared by all branches and its helper driver. The owner persists/claims
 * [initial] before constructing this journal, and revokes the writer before handing the run to another owner.
 * Uncertain writes retry the exact proposal, not author work. No feedback escapes until the write is confirmed.
 */
internal class WorkflowRunJournal(
    initial: WorkflowRun,
    private val storage: HarnessRunStorage,
    private val feedback: suspend (WorkflowStep) -> Unit,
) : WorkflowStepJournal {
    private val lock = Mutex()
    private val snapshot = MutableStateFlow(initial)
    private val log = Log.tag("HarnessWorkflow")
    val state = snapshot.asStateFlow()
    override val steps: List<WorkflowStep> get() = state.value.steps

    override suspend fun prepare(key: StepKey, digest: String): WorkflowStep = lock.withLock {
        current().steps.find { it.key == key }?.let {
            if (it.promptSha != digest) throw WorkflowStepFailed(WorkflowFailure.Diverged)
            return@withLock it
        }
        if (steps.size >= HarnessLimits.STEPS) throw WorkflowStepFailed(WorkflowFailure.Error)
        val step = WorkflowStep(key, digest)
        persist(step)
        step
    }

    override suspend fun complete(key: StepKey, result: JsonElement) = lock.withLock {
        val old = checkNotNull(current().steps.find { it.key == key }) { "Step must be prepared" }
        persist(old.copy(phase = StepPhase.Completed, result = result, failure = null))
    }

    override suspend fun fail(key: StepKey, reason: WorkflowFailure) = lock.withLock {
        val old = checkNotNull(current().steps.find { it.key == key }) { "Step must be prepared" }
        persist(old.copy(phase = StepPhase.Failed, result = null, failure = reason))
    }

    /** Helper driver supplies captured identity; the durable storage checks monotonic attempt/native progress. */
    suspend fun record(step: WorkflowStep) = lock.withLock { persist(step) }

    /** Driver owns terminal cleanup; local replay never infers that a native helper has stopped. */
    suspend fun replace(transform: (WorkflowRun) -> WorkflowRun): WorkflowRun = lock.withLock {
        if (snapshot.value.status != WorkflowStatus.Running) throw WorkflowExecutionUnavailable()
        val proposed = transform(snapshot.value)
        check(proposed.id == snapshot.value.id && proposed.driverGeneration == snapshot.value.driverGeneration)
        save(proposed)
        proposed
    }

    private fun current(): WorkflowRun {
        val run = snapshot.value
        if (run.status != WorkflowStatus.Running || run.cancellation != null) throw WorkflowExecutionUnavailable()
        return run
    }

    private suspend fun persist(step: WorkflowStep) {
        val run = current()
        val old = run.steps.find { it.key == step.key }
        if (old == step) return
        if (old?.phase == StepPhase.Completed || old?.phase == StepPhase.Failed) {
            throw WorkflowStepFailed(WorkflowFailure.Diverged)
        }
        val steps = if (old == null) run.steps + step else run.steps.map { if (it.key == step.key) step else it }
        save(run.copy(steps = steps))
        feedback(step)
    }

    @HighFrequency
    private suspend fun save(proposed: WorkflowRun) {
        log.v { "persist workflow progress" }
        var hasReportedUncertainty = false
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                if (!storage.save(proposed, snapshot.value.driverGeneration)) throw WorkflowExecutionUnavailable()
                snapshot.value = proposed
                return
            } catch (error: HarnessStorageUncertain) {
                if (!hasReportedUncertainty) log.w(error) { "Workflow journal write awaits confirmation" }
                hasReportedUncertainty = true
                delay(250.milliseconds)
            } catch (error: HarnessStorageException) {
                log.w(error) { "Workflow journal cannot continue" }
                throw WorkflowExecutionUnavailable()
            }
        }
    }
}
