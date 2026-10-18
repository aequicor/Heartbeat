package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.process.AttemptFailure
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
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
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.ownershipIntent
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDurableProcessesEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.services.confirmProcessWorkflow
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val toggles: FeatureToggles,
) {
    private val log = Log.tag("HarnessProcessWorkflow")

    suspend fun start(
        owner: HarnessInstanceTarget,
        key: ProcessWorkflowKey,
        name: ItemName,
        input: JsonObject,
        origin: HarnessCallOrigin,
    ): RunId = data.processMutex.withLock {
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
        val digest = reservationDigest(launch, owner, input, record)
        val proposal = ProcessWorkflow(RunId("wf_" + Uuid.random().toHexString()), owner.request.item.id, digest)
        val reduction = processes.applyHost(
            key.process,
            ProcessCommand.StartWorkflow(
                key,
                proposal,
                record.inputRevision,
                record.subject,
                currentDefinition(record, owner),
            ),
        )
        val reserved = when (reduction) {
            is ProcessReduction.Changed -> reduction.record
            is ProcessReduction.Unchanged -> reduction.record
            is ProcessReduction.Rejected -> error("Workflow reservation conflicts with the process")
        }
        val claim = checkNotNull(claim(reserved, key, owner)) { "Workflow reservation is unavailable" }
        val existingRun = storage.find(claim.run)
        val admission = workflowAdmission(owner, processes, reserved, claim, existingRun)
        check(admission != NativeOwnershipAdmission.Unavailable) { "Workflow ownership is unavailable" }
        check(existingRun != null || admission !is NativeOwnershipAdmission.Recover) {
            "Uncertain workflow admission requires reconciliation"
        }
        val permit = when (admission) {
            is NativeOwnershipAdmission.Fresh -> admission.intent.permit
            is NativeOwnershipAdmission.Recover -> admission.intent.permit
            NativeOwnershipAdmission.Legacy -> null
            NativeOwnershipAdmission.Unavailable -> error("Workflow admission is unavailable")
        }
        val binding = WorkflowProcessOwner(owner.request.item.id, key, digest, permit)
        try {
            releaseSettled(owner.request.harness.id)
            validateStart(claim, reserved, key, launch.harness.id)
            launches.value.startReserved(launch, binding, claim.run) { sessions.isCurrent(owner) }.run
        } catch (error: CancellationException) {
            // No journal record means no native workflow: close the reservation so no later dispatch can use it.
            withContext(NonCancellable) { releaseUnstartedReservation(processes, key, claim) }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { releaseUnstartedReservation(processes, key, claim) }
            throw error
        }
    }

    /** A pinned process keeps its safeguards even after the feature flag is switched off. */
    private suspend fun currentDefinition(record: ProcessRecord, owner: HarnessInstanceTarget): ProcessDefinition? =
        if (record.definition != null || toggles.get(HarnessDurableProcessesEnabled)) {
            ProcessDefinition(owner.request.item.id, owner.request.harness.revision)
        } else {
            null
        }

    /** Identity of the reserved request: a keyed retry must present the same harness, workflow, input and revision. */
    private fun reservationDigest(
        launch: WorkflowLaunch,
        owner: HarnessInstanceTarget,
        input: JsonObject,
        record: ProcessRecord,
    ): String {
        val parts = listOf(
            Json.encodeToString(launch.harness),
            launch.workflow.id.value,
            owner.request.item.id.value,
            canonical(input).toString(),
            record.inputRevision.toString(),
            record.subject?.value.orEmpty(),
        )
        return parts.joinToString("\n").encodeUtf8().sha256().hex()
    }

    /** Closes [claim] only while its run was never journaled; a journaled run is driven by restore instead. */
    private suspend fun validateStart(
        claim: ProcessWorkflow,
        reserved: ProcessRecord,
        key: ProcessWorkflowKey,
        harness: HarnessId,
    ) {
        if (storage.find(claim.run) == null) {
            check(!claim.isDispatchClosed) { "Archived workflow requires reconciliation" }
            check(reserved.inFlight?.number == key.attempt) { "Workflow attempt is already closed" }
            val count = storage.load().count { it.harness == harness && it.process != null && !it.isProcessReleased }
            check(count < ProcessLimits.PROCESSES * (ProcessLimits.HISTORY + 1)) { "Workflow journal capacity reached" }
        }
    }

    private suspend fun workflowAdmission(
        owner: HarnessInstanceTarget,
        processes: HarnessScriptProcesses,
        reserved: ProcessRecord,
        claim: ProcessWorkflow,
        existingRun: WorkflowRun?,
    ): NativeOwnershipAdmission = if (existingRun == null) {
        processes.admitEffect(reserved, OwnershipPermitKind.Workflow, claim.run.value)
    } else {
        val intent = existingRun.ownershipIntent()
        if (intent != null) {
            check(data.ownership?.isCurrent(intent) == true) { "Workflow ownership was revoked" }
            NativeOwnershipAdmission.Recover(intent)
        } else {
            check(
                reserved.ownershipKey == null && data.ownership?.binding(owner.request.harness.id, reserved) == null,
            ) {
                "Owned workflow has no durable admission"
            }
            NativeOwnershipAdmission.Legacy
        }
    }

    private suspend fun releaseUnstartedReservation(
        processes: HarnessScriptProcesses,
        key: ProcessWorkflowKey,
        claim: ProcessWorkflow,
    ) {
        if (claim.isDispatchClosed || storage.find(claim.run) != null) return
        try {
            val cancelled = processes.applyHost(key.process, ProcessCommand.CancelWorkflow(key, claim))
            if (cancelled !is ProcessReduction.Rejected && storage.find(claim.run) == null) {
                val settled = processes.applyHost(key.process, ProcessCommand.SettleWorkflow(key, claim))
                if (settled is ProcessReduction.Rejected) return
                // This producer joined before the closed reservation receipt; no future dispatch can use it.
                processes.findEffect(key.process, key.incarnation, OwnershipPermitKind.Workflow, claim.run.value)
                    ?.let { processes.completeEffect(it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unstarted workflow reservation was not released" }
        }
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
        data.processMutex.withLock {
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
            run == null && claim.isDispatchClosed -> ScriptWorkflowCancellation.Unavailable
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
        val result = processes.applyHost(key.process, ProcessCommand.CancelWorkflow(key, claim))
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
    ): ScriptWorkflowState {
        val isCancelled = record.history.any {
            it.attempt.number == key.attempt && it.outcome == AttemptOutcome.Failed(AttemptFailure.Cancelled)
        }
        return if ((!claim.isDispatchClosed || claim.isSettled) && isCancelled) {
            ScriptWorkflowState.Found(claim.run, WorkflowStatus.Failed(WorkflowFailure.Cancelled), true)
        } else {
            ScriptWorkflowState.Unavailable
        }
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
        data.processMutex,
        definition = ProcessDefinition(owner.request.item.id, owner.request.harness.revision),
        isVersioningEnabled = { toggles.get(HarnessDurableProcessesEnabled) },
        archives = data.archives,
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
        run.process?.copy(ownership = null) == WorkflowProcessOwner(owner.request.item.id, key, claim.digest)

    /** Process highwater and lifetime are checked before releasing any journal protection. */
    suspend fun releaseSettled(harness: HarnessId) {
        val raw = data.read(harness, HarnessScriptProcesses.STORAGE_KEY) {}
        val records = decodeProcessRecords(raw.value)
        val terminal = storage.load().filter {
            it.process != null && it.harness == harness &&
                !it.isProcessReleased && it.status != WorkflowStatus.Running
        }
        for (run in terminal) {
            val binding = checkNotNull(run.process)
            val record = records[binding.key.process]
            val isCurrent = record?.incarnation == binding.key.incarnation &&
                record.inFlight?.number == binding.key.attempt
            if (!isCurrent && record?.incarnation == binding.key.incarnation) {
                data.confirmProcessWorkflow(harness, run, clock.now())
            }
            if (!isCurrent && !storage.releaseProcess(run.id)) {
                throw HarnessStorageUncertain()
            }
        }
    }

    private fun decodeProcessRecords(value: JsonElement?): Map<ProcessId, ProcessRecord> = try {
        HarnessScriptProcesses.decodeStored(value).associateBy { it.id }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Decoder messages may quote stored script data. Only a safe failure reaches driver logging.
        val failure = IllegalStateException("Stored process is unreadable")
        log.w(failure) { "Stored process could not be read (${error::class.simpleName.orEmpty()})" }
        throw failure
    }
}

/** Object ordering does not change JSON input identity; array ordering does. */
private fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
    is JsonArray -> JsonArray(element.map(::canonical))
    is JsonPrimitive -> element
}
