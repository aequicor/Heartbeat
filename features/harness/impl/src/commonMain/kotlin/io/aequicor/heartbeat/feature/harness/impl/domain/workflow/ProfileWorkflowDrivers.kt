package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * One serial owner per run. collectLatest revokes and joins the previous generation before a replacement can
 * touch storage or helper leases. Non-cooperative author code therefore delays the barrier instead of granting
 * concurrent ownership. Requests are nonblocking; library lifecycle polls the confirmed pause barrier. A slot and
 * its follower live only until the run's terminal record is settled, unless a newer request arrived meanwhile.
 */
internal class ProfileWorkflowDrivers(
    private val scope: CoroutineScope,
    private val execution: WorkflowRunExecution,
    private val isProjectionCurrent: (RunId, Long) -> Boolean,
) {
    private val lock = Mutex()
    private val slots = MutableStateFlow<Map<RunId, WorkflowDriverSlot>>(emptyMap())
    private val log = Log.tag("HarnessWorkflow")

    /**
     * Runs that currently have a driver slot and follower. Only tests observe it to prove that terminal runs retire
     * their slots; production code must not branch on this snapshot.
     */
    val activeRuns: Set<RunId> get() = slots.value.keys

    suspend fun drive(run: WorkflowRun) = offer(WorkflowDriverRequest(run, isPause = false))

    /** Pause effects carry the pre-transition records; suspension itself advances their projection fence. */
    suspend fun pause(run: WorkflowRun) = offer(
        WorkflowDriverRequest(run.copy(driverGeneration = run.driverGeneration + 1), isPause = true),
    )

    /** Restored/suspended records already contain the projection fence and have no previous live producer. */
    suspend fun pauseCurrent(run: WorkflowRun) = offer(WorkflowDriverRequest(run, isPause = true))

    fun isPaused(run: RunId, generation: Long): Boolean = slots.value[run]?.let {
        val request = it.request.value
        request?.isPause == true && request.run.driverGeneration >= generation &&
            it.settled.value >= request.run.driverGeneration
    } ?: true

    private suspend fun offer(request: WorkflowDriverRequest) = lock.withLock {
        if (!isProjectionCurrent(request.run.id, request.run.driverGeneration)) return@withLock
        val slot = slots.value[request.run.id] ?: WorkflowDriverSlot().also { created ->
            slots.update { it + (request.run.id to created) }
            log.v { "Workflow driver slot created" }
            created.follower = scope.launch { follow(request.run.id, created) }
        }
        slot.request.update { old ->
            when {
                old == null -> request

                old.run.driverGeneration > request.run.driverGeneration -> old

                old.run.driverGeneration == request.run.driverGeneration &&
                    old.run.cancellation != null && request.run.cancellation == null -> old

                else -> request
            }
        }
    }

    private suspend fun follow(run: RunId, slot: WorkflowDriverSlot) {
        slot.request.filterNotNull().collectLatest { request ->
            var hasReportedFailure = false
            var isTerminal = attempt(slot, request, hasReportedFailure)
            while (isTerminal == null) {
                hasReportedFailure = true
                delay(1.seconds)
                isTerminal = attempt(slot, request, hasReportedFailure)
            }
            slot.settled.value = request.run.driverGeneration
            if (isTerminal) retire(run, slot, request)
        }
    }

    /** A terminal run needs no owner: drop its slot and stop the follower, unless a newer request replaced it. */
    private suspend fun retire(run: RunId, slot: WorkflowDriverSlot, request: WorkflowDriverRequest) {
        val isRetired = lock.withLock {
            (slots.value[run] === slot && slot.request.value == request).also { isCurrent ->
                if (isCurrent) slots.update { it - run }
            }
        }
        if (isRetired) {
            log.v { "Workflow driver slot retired" }
            slot.follower?.cancel()
        }
    }

    /** One pass of the exact request: terminal, settled, or null to retry after an uncertain outcome. */
    private suspend fun attempt(
        slot: WorkflowDriverSlot,
        request: WorkflowDriverRequest,
        isReported: Boolean,
    ): Boolean? {
        currentCoroutineContext().ensureActive()
        return try {
            if (request.isPause) {
                execution.pause(request.run)
            } else {
                execution.execute(request.run) {
                    slot.request.value == request && isProjectionCurrent(request.run.id, request.run.driverGeneration)
                }
            }
        } catch (error: WorkflowExecutionUnavailable) {
            if (!isReported) log.w(error) { "Workflow driver awaits reconciliation" }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Host infrastructure failure: author code failures are journaled typed results and never reach here.
            if (!isReported) log.w(error) { "Workflow driver awaits durable IO" }
            null
        }
    }
}

private data class WorkflowDriverRequest(val run: WorkflowRun, val isPause: Boolean)

private class WorkflowDriverSlot {
    val request = MutableStateFlow<WorkflowDriverRequest?>(null)
    val settled = MutableStateFlow(-1L)
    var follower: Job? = null
}
