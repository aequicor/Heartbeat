package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.process.AttemptFailure
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflowCancellation
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflowState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowProcessOwner
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRejection
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessSessionAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Process CAS owns the identity before dispatch. Only a current script activation can use the reservation. */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessProcessWorkflows(
    private val sessions: HarnessSessionAccess,
    private val data: HarnessDataStorage,
    private val storage: HarnessRunStorage,
    private val launches: Lazy<HarnessWorkflowLaunches>,
    private val runs: Lazy<HarnessRunsMachine>,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private val log = Log.tag("HarnessProcessWorkflow")

    suspend fun start(
        owner: HarnessInstanceTarget,
        key: ProcessWorkflowKey,
        name: ItemName,
        input: JsonObject,
        origin: HarnessCallOrigin,
    ): RunId = lock.withLock {
        checkAccess(owner)
        check(!origin.isHookRestricted) { "Hooks cannot start workflows" }
        val processes = processes(owner)
        val record = processes.get(key.process)
        check(record != null && record.incarnation == key.incarnation) { "Workflow process is unavailable" }
        val launch = launches.value.prepare(
            owner.request.harness.id,
            name,
            input,
            null,
            null,
            null,
            origin,
            WorkflowOrigin.Script,
        )
        check(launch.harness == owner.request.harness) { "Script admission changed" }
        val digest = (
            Json.encodeToString(
                launch.harness,
            ) + "\n" + launch.workflow.id.value + "\n" + owner.request.item.id.value + "\n" +
                (
                    canonical(
                        input,
                    ).toString() + "\n" + record.inputRevision + "\n" + record.subject?.value
                )
        ).encodeUtf8().sha256().hex()
        val proposal = ProcessWorkflow(RunId("wf_" + Uuid.random().toHexString()), owner.request.item.id, digest)
        val reduction = processes.apply(
            key.process,
            ProcessCommand.StartWorkflow(key, proposal, record.inputRevision, record.subject),
        )
        val reserved = when (reduction) {
            is ProcessReduction.Changed -> reduction.record
            is ProcessReduction.Unchanged -> reduction.record
            is ProcessReduction.Rejected -> error("Workflow reservation conflicts with the process")
        }
        val claim = checkNotNull(claim(reserved, key, owner)) { "Workflow reservation is unavailable" }
        releaseSettled(owner, processes)
        val stored = storage.find(claim.run)
        if (stored == null) {
            // A closed or pruned attempt must never create an executor, even after retention removed its result.
            check(reserved.inFlight?.number == key.attempt) { "Workflow attempt is already closed" }
            val count = storage.load().count {
                it.harness == launch.harness.id && it.process != null &&
                    !it.isProcessReleased
            }
            check(count < ProcessLimits.PROCESSES * (ProcessLimits.HISTORY + 1)) { "Workflow journal capacity reached" }
        }
        val binding = WorkflowProcessOwner(owner.request.item.id, key, digest)
        launches.value.startReserved(launch, binding, claim.run) { sessions.isCurrent(owner) }.run
    }

    suspend fun get(owner: HarnessInstanceTarget, key: ProcessWorkflowKey): ScriptWorkflowState {
        checkAccess(owner)
        return try {
            observe(owner, key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            log.w(HarnessStorageUncertain()) {
                "Process workflow observation is unavailable (${error::class.simpleName.orEmpty()})"
            }
            ScriptWorkflowState.Unavailable
        }
    }

    private suspend fun observe(owner: HarnessInstanceTarget, key: ProcessWorkflowKey): ScriptWorkflowState {
        val record = processes(owner).get(key.process)
        val claim = record?.let { claim(it, key, owner) }
        if (claim == null) return ScriptWorkflowState.Missing
        val run = storage.find(claim.run)
        return when {
            run == null -> cancelledReservation(record, key, claim)
            !matches(run, owner, key, claim) -> ScriptWorkflowState.Missing
            else -> ScriptWorkflowState.Found(run.id, run.status, run.cancellation != null)
        }
    }

    suspend fun cancel(owner: HarnessInstanceTarget, key: ProcessWorkflowKey): ScriptWorkflowCancellation =
        lock.withLock {
            checkAccess(owner)
            try {
                cancelOwned(owner, key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                log.w(HarnessStorageUncertain()) {
                    "Process workflow cancellation is unavailable (${error::class.simpleName.orEmpty()})"
                }
                ScriptWorkflowCancellation.Unavailable
            }
        }

    private suspend fun cancelOwned(
        owner: HarnessInstanceTarget,
        key: ProcessWorkflowKey,
    ): ScriptWorkflowCancellation {
        val processes = processes(owner)
        val record = processes.get(key.process)
        val claim = record?.let { claim(it, key, owner) }
        if (claim == null) return ScriptWorkflowCancellation.Missing
        val run = storage.find(claim.run)
        return when {
            run == null -> cancelReservation(processes, record, key, claim)
            !matches(run, owner, key, claim) -> ScriptWorkflowCancellation.Missing
            run.status != WorkflowStatus.Running -> ScriptWorkflowCancellation.Finished
            else -> requestCancellation(owner, run)
        }
    }

    private suspend fun cancelReservation(
        processes: HarnessScriptProcesses,
        record: ProcessRecord,
        key: ProcessWorkflowKey,
        claim: ProcessWorkflow,
    ): ScriptWorkflowCancellation {
        val outcome = AttemptOutcome.Failed(AttemptFailure.Cancelled)
        val result = processes.apply(key.process, ProcessCommand.CancelWorkflow(key, claim))
        // Missing protected journal proves no native submission; closing this attempt prevents any later dispatch.
        return when {
            result is ProcessReduction.Changed -> ScriptWorkflowCancellation.Finished

            record.history.any { it.attempt.number == key.attempt && it.outcome == outcome } ->
                ScriptWorkflowCancellation.Finished

            else -> ScriptWorkflowCancellation.Unavailable
        }
    }

    private fun cancelledReservation(
        record: ProcessRecord,
        key: ProcessWorkflowKey,
        claim: ProcessWorkflow,
    ): ScriptWorkflowState = if (record.history.any {
            it.attempt.number == key.attempt && it.outcome == AttemptOutcome.Failed(AttemptFailure.Cancelled)
        }
    ) {
        ScriptWorkflowState.Found(claim.run, WorkflowStatus.Failed(WorkflowFailure.Cancelled), true)
    } else {
        ScriptWorkflowState.Unavailable
    }

    private suspend fun requestCancellation(
        owner: HarnessInstanceTarget,
        run: WorkflowRun,
    ): ScriptWorkflowCancellation {
        val machine = runs.value
        if (machine.state.value is HarnessRunsState.Idle || machine.state.value is HarnessRunsState.Failed) {
            machine.send(HarnessRunsIntent.Internal.Start)
        }
        val ready = withTimeoutOrNull(5.seconds) { machine.state.first { it is HarnessRunsState.Ready } }
            as? HarnessRunsState.Ready
        if (ready == null) return ScriptWorkflowCancellation.Unavailable
        checkAccess(owner)
        return sendCancellation(machine, owner, run)
    }

    private suspend fun sendCancellation(
        machine: HarnessRunsMachine,
        owner: HarnessInstanceTarget,
        run: WorkflowRun,
    ): ScriptWorkflowCancellation = coroutineScope {
        val request = RequestId(Uuid.random().toHexString())
        val receipt = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first {
                (it is HarnessRunsOutput.CancellationRequested && it.requestId == request) ||
                    (it is HarnessRunsOutput.Rejected && it.requestId == request)
            }
        }
        try {
            val sent = machine.send(
                HarnessRunsIntent.Internal.CancelStoredRun(
                    request,
                    run,
                    WorkflowViewer.Script(owner.request.harness.id, checkNotNull(run.process)),
                ),
            )
            val result = if (sent == SendResult.Accepted) withTimeoutOrNull(5.seconds) { receipt.await() } else null
            when (result) {
                is HarnessRunsOutput.CancellationRequested -> ScriptWorkflowCancellation.Requested

                is HarnessRunsOutput.Rejected -> if (result.reason == WorkflowRejection.AlreadyFinished) {
                    ScriptWorkflowCancellation.Finished
                } else {
                    ScriptWorkflowCancellation.Unavailable
                }

                is HarnessRunsOutput.Started, is HarnessRunsOutput.RunFinished, null ->
                    ScriptWorkflowCancellation.Unavailable
            }
        } finally {
            receipt.cancel()
        }
    }

    private fun processes(owner: HarnessInstanceTarget): HarnessScriptProcesses = HarnessScriptProcesses(
        HarnessScriptData(owner.request.harness.id, owner.access, data),
        clock,
    )

    private fun checkAccess(owner: HarnessInstanceTarget) {
        check(sessions.isCurrent(owner)) { "Script workflow access is unavailable" }
    }

    private fun claim(record: ProcessRecord, key: ProcessWorkflowKey, owner: HarnessInstanceTarget): ProcessWorkflow? {
        if (record.id != key.process || record.incarnation != key.incarnation) return null
        val attempt = record.inFlight?.takeIf { it.number == key.attempt }
            ?: record.history.firstOrNull { it.attempt.number == key.attempt }?.attempt
        return attempt?.takeIf { it.stage == key.stage }?.workflow?.takeIf { it.script == owner.request.item.id }
    }

    private fun matches(
        run: WorkflowRun,
        owner: HarnessInstanceTarget,
        key: ProcessWorkflowKey,
        claim: ProcessWorkflow,
    ): Boolean = run.harness == owner.request.harness.id &&
        run.process == WorkflowProcessOwner(owner.request.item.id, key, claim.digest)

    /** Process highwater and lifetime are checked before releasing any journal protection. */
    private suspend fun releaseSettled(owner: HarnessInstanceTarget, processes: HarnessScriptProcesses) {
        val records = processes.list().associateBy { it.id }
        val terminal = storage.load().filter {
            it.process != null && it.harness == owner.request.harness.id &&
                !it.isProcessReleased && it.status != WorkflowStatus.Running
        }
        for (run in terminal) {
            val binding = checkNotNull(run.process)
            val record = records[binding.key.process]
            val isCurrent = record?.incarnation == binding.key.incarnation &&
                record.inFlight?.number == binding.key.attempt
            if (!isCurrent && !storage.releaseProcess(run.id)) {
                throw HarnessStorageUncertain()
            }
        }
    }
}

/** Object ordering does not change JSON input identity; array ordering does. */
private fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
    is JsonArray -> JsonArray(element.map(::canonical))
    is JsonPrimitive -> element
}
