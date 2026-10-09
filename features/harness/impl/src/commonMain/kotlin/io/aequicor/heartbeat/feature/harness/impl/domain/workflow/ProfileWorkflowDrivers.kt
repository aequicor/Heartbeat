package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * concurrent ownership. Requests are nonblocking; library lifecycle polls the confirmed pause barrier.
 */
internal class ProfileWorkflowDrivers(
    private val scope: CoroutineScope,
    private val execution: WorkflowRunExecution,
    private val isProjectionCurrent: (RunId, Long) -> Boolean,
) {
    private val lock = Mutex()
    private val slots = MutableStateFlow<Map<RunId, WorkflowDriverSlot>>(emptyMap())
    private val log = Log.tag("HarnessWorkflow")

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
            scope.launch { follow(created) }
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

    private suspend fun follow(slot: WorkflowDriverSlot) {
        slot.request.filterNotNull().collectLatest { request ->
            var hasReportedFailure = false
            while (!attempt(slot, request, hasReportedFailure)) {
                hasReportedFailure = true
                delay(1.seconds)
            }
            slot.settled.value = request.run.driverGeneration
        }
    }

    /** One pass of the exact request; false keeps the same owner retrying after an uncertain outcome. */
    private suspend fun attempt(
        slot: WorkflowDriverSlot,
        request: WorkflowDriverRequest,
        isReported: Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        return try {
            if (request.isPause) {
                execution.pause(request.run)
            } else {
                execution.execute(request.run) {
                    slot.request.value == request && isProjectionCurrent(request.run.id, request.run.driverGeneration)
                }
            }
            true
        } catch (error: WorkflowExecutionUnavailable) {
            if (!isReported) log.w(error) { "Workflow driver awaits reconciliation" }
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!isReported) log.w(harnessScriptFailure(error)) { "Workflow driver awaits durable IO" }
            false
        }
    }
}

private data class WorkflowDriverRequest(val run: WorkflowRun, val isPause: Boolean)

private class WorkflowDriverSlot {
    val request = MutableStateFlow<WorkflowDriverRequest?>(null)
    val settled = MutableStateFlow(-1L)
}
