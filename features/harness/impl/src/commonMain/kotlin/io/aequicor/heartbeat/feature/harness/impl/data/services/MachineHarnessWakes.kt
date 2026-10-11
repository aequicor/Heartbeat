package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeReceipt
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Matching output subscription is attached before Schedule; state is an additional positive ACK, never a retry. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessWakes(private val machines: MachineRegistry, private val toggles: FeatureToggles) :
    HarnessWakePort {
    private val log = Log.tag("HarnessServices")

    override fun snapshot(): SchedulerState.Ready? =
        machines.find(SchedulerMachineKey)?.state?.value as? SchedulerState.Ready

    @HighFrequency
    override suspend fun schedule(request: WakeRequest, at: Instant): HarnessWakeReceipt = coroutineScope {
        log.v { "submit owned wake to scheduler" }
        val machine = machines.find(SchedulerMachineKey) ?: return@coroutineScope HarnessWakeReceipt.Rejected
        if (!canSchedule(machine, request)) return@coroutineScope HarnessWakeReceipt.Rejected
        val receipt = CompletableDeferred<HarnessWakeReceipt>()
        val observer = observeReceipt(machine, request, receipt)
        try {
            submit(machine, request, at, receipt)
        } finally {
            observer.cancel()
        }
    }

    private suspend fun canSchedule(machine: Scheduler, request: WakeRequest): Boolean {
        val ready = machine.state.value as? SchedulerState.Ready
        return toggles.get(SchedulerEnabled) && ready != null && ready.wakes.none { it.id == request.id }
    }

    private fun CoroutineScope.observeReceipt(
        machine: Scheduler,
        request: WakeRequest,
        receipt: CompletableDeferred<HarnessWakeReceipt>,
    ): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        val output = machine.outputs.first { it.matches(request.id) }
        receipt.complete(
            when {
                output is SchedulerOutput.Rejected -> HarnessWakeReceipt.Rejected
                output is SchedulerOutput.Scheduled && output.wake.request == request -> HarnessWakeReceipt.Scheduled
                else -> HarnessWakeReceipt.Unknown
            },
        )
    }

    private suspend fun submit(
        machine: Scheduler,
        request: WakeRequest,
        at: Instant,
        receipt: CompletableDeferred<HarnessWakeReceipt>,
    ): HarnessWakeReceipt {
        if (machines.find(SchedulerMachineKey) !== machine) return HarnessWakeReceipt.Rejected
        // Pin the public registry handle: re-resolving the key could mutate a replacement profile machine.
        val sent = machine.send(SchedulerIntent.Public.Schedule(request, at))
        val ready = machine.state.value as? SchedulerState.Ready
        return when {
            machines.find(SchedulerMachineKey) !== machine -> HarnessWakeReceipt.Unknown
            sent != SendResult.Accepted -> HarnessWakeReceipt.Rejected
            receipt.isCompleted -> receipt.await()
            ready?.wakes?.any { it.request == request } == true -> HarnessWakeReceipt.Scheduled
            else -> withTimeoutOrNull(5.seconds) { receipt.await() } ?: HarnessWakeReceipt.Unknown
        }
    }

    @HighFrequency
    override suspend fun cancel(request: WakeRequest, cause: EventOrigin?): Boolean {
        val id = request.id
        log.v { "cancel pending owned wake" }
        val machine = machines.find(SchedulerMachineKey) ?: return false
        val before = machine.state.value as? SchedulerState.Ready
        return if (before?.canCancel(request) == true && machines.find(SchedulerMachineKey) === machine) {
            val sent = machine.send(SchedulerIntent.Public.Cancel(id, cause = cause, expectedRequest = request))
            val after = machine.state.value as? SchedulerState.Ready
            sent == SendResult.Accepted && machines.find(SchedulerMachineKey) === machine &&
                after?.wakes?.none { it.id == id } == true
        } else {
            false
        }
    }
}

private typealias Scheduler = MachineRef<SchedulerState, SchedulerIntent.Public, SchedulerOutput>

private fun SchedulerOutput.matches(id: WakeId): Boolean =
    (this is SchedulerOutput.Scheduled && wake.id == id) || (this is SchedulerOutput.Rejected && this.id == id)

private fun SchedulerState.Ready.canCancel(request: WakeRequest): Boolean =
    request.id !in delivering && wakes.any { it.request == request }
