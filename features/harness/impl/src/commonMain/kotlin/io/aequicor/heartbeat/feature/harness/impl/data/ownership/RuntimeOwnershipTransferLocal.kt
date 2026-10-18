package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.process.AttemptFailure
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessExport
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessImport
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAttempt
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.portableCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.prepareImport
import io.aequicor.heartbeat.feature.harness.api.process.reduce
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.PortableGitHubAdapter
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.PortableGitHubExport
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.ProtectedImportedGitHub
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipUnavailable
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.ownershipIntent
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipDrainReceipt
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipLocalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipReconciliationReceipt
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferContext
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferLocal
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.services.confirmProcessWorkflow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okio.ByteString.Companion.encodeUtf8

/** Real local stop/import adapter. Every old callback is pinned to the source fence and immutable run identity. */
internal class RuntimeOwnershipTransferLocal(
    private val target: HarnessInstanceTarget,
    private val host: OwnershipRuntimeHost,
    private val runs: HarnessRunStorage,
    private val cancelWorkflow: suspend (io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey) -> Unit,
    private val github: PortableGitHubAdapter,
    private val imported: ProtectedImportedGitHub,
) : OwnershipTransferLocal {
    private val request get() = target.request
    private val access get() = target.access
    private val data get() = host.data
    private val runtime get() = host.runtime
    private val workspace get() = host.workspace
    private val decisions get() = host.decisions
    private val clock get() = host.clock

    private val log = Log.tag("OwnershipTransferLocal")
    internal suspend fun records(): List<ProcessRecord> = HarnessScriptProcesses.decodeStored(
        data.read(request.harness.id, HarnessScriptProcesses.STORAGE_KEY) { check(access.isActive) }.value,
    )

    /** Rejects unresolved legacy producers during enrollment. Caller holds the writer barrier. */
    internal suspend fun canEnroll(record: ProcessRecord): Boolean {
        val entries = decisions.ready()?.entries ?: return false
        val hasUnknown = entries.filter { it.ownership == null }.any {
            it.decision.binding.harness == request.harness.id &&
                it.decision.binding.process.process == record.id &&
                it.decision.binding.process.incarnation == record.incarnation &&
                it.decision.phase == ProcessDecisionPhase.Executing
        }
        val hasLive = runs.load().any {
            it.harness == request.harness.id && it.process?.key?.process == record.id &&
                it.process?.key?.incarnation == record.incarnation && it.status == WorkflowStatus.Running
        }
        return !hasUnknown && !hasLive &&
            github.exportReceipts(
                record.local(request.harness.id),
                record,
                ownershipDefinitionDigest(request),
                true,
            ) is PortableGitHubExport.Ready
    }

    override suspend fun verifyBinding(binding: OwnershipTransferBinding): Boolean {
        if (!access.isActive || binding.definition != ProcessDefinition(
                request.item.id,
                request.harness.revision,
            )
        ) {
            return false
        }
        if (binding.definitionDigest != ownershipDefinitionDigest(request)) return false
        val storedBinding = runtime.journal.binding(binding.local())
        if (storedBinding != null && storedBinding.workspace?.value != binding.workspace) return false
        val current = records().singleOrNull { it.id == binding.process }
        val isLocal = current == null || (
            current.incarnation == binding.incarnation &&
                current.definition == binding.definition && current.ownershipKey == binding.key
        )
        val task = runtime.task(binding.identity.repository, binding.identity.kind, binding.identity.number)
        return isLocal && task?.canonical == binding.identity.canonical &&
            workspace.head(WorkspaceRef(binding.workspace), binding.identity) != null
    }

    override suspend fun closeAdmissions(
        context: OwnershipTransferContext,
    ): OwnershipLocalResult<io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership> =
        data.processMutex.withLock {
            val current = snapshot(context)
            if (current == context.snapshot) OwnershipLocalResult.Confirmed(current) else OwnershipLocalResult.Pending
        }

    override suspend fun drainAndSeal(context: OwnershipTransferContext): OwnershipLocalResult<OwnershipDrainReceipt> {
        if (!drain(context)) return OwnershipLocalResult.Pending
        return data.processMutex.withLock {
            // A stale coordinator GET cannot revoke the newer epoch already persisted by local cancellation.
            val localFence = runtime.journal.binding(context.binding.local())?.fence
            if (localFence != context.snapshot.fence || snapshot(context) == null) {
                return@withLock OwnershipLocalResult.Pending
            }
            val record = records().singleOrNull { it.id == context.binding.process } ?: return@withLock unavailable()
            if (record.incarnation != context.binding.incarnation) return@withLock unavailable()
            val head = workspace.head(WorkspaceRef(context.binding.workspace), context.binding.identity)
                ?: return@withLock unavailable()
            val pinned = record.copy(subject = head)
            if (!replace(record, pinned)) return@withLock OwnershipLocalResult.Pending
            seal(context, pinned)
        }
    }

    private suspend fun seal(
        context: OwnershipTransferContext,
        pinned: ProcessRecord,
    ): OwnershipLocalResult<OwnershipDrainReceipt> {
        val exported = github.exportReceipts(context.binding.local(), pinned, context.binding.definitionDigest, true)
        val manifest = (exported as? PortableGitHubExport.Ready)?.let {
            pinned.portableCheckpoint(context.binding.identity, it.receipts)
        } as? PortableProcessExport.Ready
        return if (manifest == null) {
            unavailable()
        } else {
            val current = snapshot(context)
            if (current != null && current.permits.isEmpty()) {
                OwnershipLocalResult.Confirmed(OwnershipDrainReceipt(current, manifest.checkpoint, 0, 0))
            } else {
                OwnershipLocalResult.Pending
            }
        }
    }

    private suspend fun drain(context: OwnershipTransferContext, isExporting: Boolean = true): Boolean {
        val source = checkNotNull(context.snapshot.transfer).source
        val ownedRuns = runs.load().filter {
            it.harness == context.binding.harness && it.process?.key?.process == context.binding.process &&
                it.process?.key?.incarnation == context.binding.incarnation && it.process?.ownership?.fence == source
        }
        val active = ownedRuns.filter { it.status == WorkflowStatus.Running }
        for (run in active) {
            cancelWorkflow(checkNotNull(run.process).key)
        }
        if (active.isNotEmpty()) return false
        return data.processMutex.withLock { drainJoined(context, ownedRuns, isExporting) }
    }

    private suspend fun drainJoined(
        context: OwnershipTransferContext,
        ownedRuns: List<WorkflowRun>,
        isExporting: Boolean,
    ): Boolean {
        // Join the current local generation before touching attempts or external receipts.
        val local = runtime.journal.binding(context.binding.local())
        if (local?.fence != context.snapshot.fence) return false
        if (!isExporting && local.isSourceReconciled) return true
        val isStopped = snapshot(
            context,
        ) != null && settleRuns(context, ownedRuns, isExporting) && settleUnstarted(context)
        val isReconciled = isStopped && repairMutationReceipts(
            context,
        ) && repairCommandReceipts(context) && repairGitHubReceipts(context)
        val final = if (isReconciled) snapshot(context) else null
        return final != null && final.permits.isEmpty()
    }

    private suspend fun settleRuns(
        context: OwnershipTransferContext,
        ownedRuns: List<WorkflowRun>,
        isExporting: Boolean,
    ): Boolean {
        for (run in ownedRuns) {
            if (!settleRun(context, run, isExporting)) return false
        }
        return true
    }

    private suspend fun settleRun(context: OwnershipTransferContext, run: WorkflowRun, isExporting: Boolean): Boolean {
        if (run.steps.any { it.helper != null && !it.isNativeReleased }) return false
        val record = records().singleOrNull { it.id == context.binding.process } ?: return false
        return if (!isExporting && record.inFlight?.workflow?.run == run.id) {
            // Cancellation restores local admission, not portability. Keep its native result and attempt so the
            // script can Finish after Activate; only the positively stopped native producer releases its permit.
            run.status != WorkflowStatus.Running && run.ownershipIntent()?.let { runtime.complete(it) } == true
        } else {
            closeAttempt(context, run) && recordWorkflowReceipt(context, run)
        }
    }

    private suspend fun recordWorkflowReceipt(context: OwnershipTransferContext, run: WorkflowRun): Boolean {
        data.confirmProcessWorkflow(context.binding.harness, run, clock.now())
        return true
    }

    private suspend fun repairMutationReceipts(context: OwnershipTransferContext): Boolean {
        val observed = snapshot(context) ?: return false
        val intents = runtime.journal.intents(context.binding.local()).associateBy { it.permit }
        return observed.permits.filter { it.kind == OwnershipPermitKind.Mutation }.all { permit ->
            // Joined writer barrier proves local mutation producers stopped; no native work uses these permits.
            intents[permit]?.let { runtime.complete(it) } == true
        }
    }

    private suspend fun closeAttempt(context: OwnershipTransferContext, run: WorkflowRun): Boolean {
        val record = records().singleOrNull { it.id == context.binding.process } ?: return false
        val attempt = record.inFlight
        if (attempt?.workflow?.run != run.id) return true
        val outcome = when (val status = run.status) {
            WorkflowStatus.Running -> return false

            is WorkflowStatus.Failed -> AttemptOutcome.Failed(
                if (status.reason ==
                    WorkflowFailure.Cancelled
                ) {
                    AttemptFailure.Cancelled
                } else {
                    AttemptFailure.Interrupted
                },
            )

            is WorkflowStatus.Completed -> {
                // Arbitrary result JSON may contain secrets. Only an independently sealable typed reference can move.
                val result = decodeResult(status.result) ?: return false
                AttemptOutcome.Succeeded(result)
            }
        }
        val reduced = record.reduce(ProcessCommand.Finish(attempt.number, outcome), clock.now())
        return reduced is ProcessReduction.Changed && replace(record, reduced.record)
    }

    private fun decodeResult(value: JsonElement): ResultRef? = try {
        Json.decodeFromJsonElement<ResultRef>(value)
    } catch (error: SerializationException) {
        log.w(IllegalStateException("Unsupported portable result (${error::class.simpleName.orEmpty()})")) {
            "Completed workflow has no portable typed result"
        }
        null
    } catch (error: IllegalArgumentException) {
        log.w(IllegalStateException("Unsupported portable result (${error::class.simpleName.orEmpty()})")) {
            "Completed workflow has an unsupported result reference"
        }
        null
    }

    private suspend fun settleUnstarted(context: OwnershipTransferContext): Boolean {
        val record = records().singleOrNull { it.id == context.binding.process } ?: return false
        val attempts = listOfNotNull(record.inFlight) + record.history.map { it.attempt }
        return attempts.filter { it.workflow != null }.all { settleMissingRun(context, it) }
    }

    private suspend fun settleMissingRun(context: OwnershipTransferContext, attempt: ProcessAttempt): Boolean {
        val reservation = checkNotNull(attempt.workflow)
        if (runs.find(reservation.run) != null) return true
        // Writer barrier joined the start producer; a durable run must precede every native dispatch. Inspect
        // closed history too: a previous cleanup may have committed CancelWorkflow before it was interrupted.
        val record = records().single { it.id == context.binding.process }
        val key = ProcessWorkflowKey(record.id, record.incarnation, attempt.number, attempt.stage)
        val closed = if (record.inFlight?.number == attempt.number) {
            reduceAndStore(record, ProcessCommand.CancelWorkflow(key, reservation))
        } else {
            record
        }
        val settled = closed?.let { reduceAndStore(it, ProcessCommand.SettleWorkflow(key, reservation)) }
        return settled != null && completeReservation(context, settled, reservation)
    }

    private suspend fun reduceAndStore(record: ProcessRecord, command: ProcessCommand): ProcessRecord? =
        when (val reduced = record.reduce(command, clock.now())) {
            is ProcessReduction.Rejected -> null
            is ProcessReduction.Unchanged -> reduced.record
            is ProcessReduction.Changed -> reduced.record.takeIf { replace(record, it) }
        }

    private suspend fun completeReservation(
        context: OwnershipTransferContext,
        record: ProcessRecord,
        reservation: ProcessWorkflow,
    ): Boolean {
        val intent = runtime.find(context.binding.harness, record, OwnershipPermitKind.Workflow, reservation.run.value)
        val current = snapshot(context) ?: return false
        // A proposal whose Admit never committed is not an outstanding producer. Closed admission plus the
        // joined writer proves it cannot dispatch later; absence while an active producer exists never suffices.
        return intent == null || current.permits.none { it.id == intent.permit.id } || runtime.complete(intent)
    }

    private suspend fun repairCommandReceipts(context: OwnershipTransferContext): Boolean {
        val record = records().singleOrNull { it.id == context.binding.process } ?: return false
        val ledger = decisions.ready() ?: return false
        for (entry in ledger.entries.filter {
            it.command != null && it.decision.phase == ProcessDecisionPhase.Completed &&
                it.decision.binding.harness == context.binding.harness &&
                it.decision.binding.process.process == record.id &&
                it.decision.binding.process.incarnation == record.incarnation
        }) {
            val intent = runtime.find(context.binding.harness, record, OwnershipPermitKind.Command, entry.decision.id)
            if (intent != null && !runtime.complete(intent)) return false
        }
        return true
    }

    private suspend fun repairGitHubReceipts(context: OwnershipTransferContext): Boolean {
        val receipts = github.reconcileAndCollectNative(context.binding.local()) ?: return false
        for (receipt in receipts) {
            val record = records().singleOrNull { it.id == context.binding.process } ?: return false
            val operation = receipt.operation
            val result = receipt.result
            val isSettled = if (result != null) {
                confirmGitHubWrite(record, operation.key, operation.marker, result)
            } else {
                settleUnsentGitHubWrite(record, operation)
            }
            if (!isSettled) return false
            val intent = runtime.find(context.binding.harness, record, OwnershipPermitKind.GitHub, operation.marker)
            if (intent != null && !runtime.complete(intent)) return false
        }
        return true
    }

    private suspend fun settleUnsentGitHubWrite(
        record: ProcessRecord,
        operation: io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation,
    ): Boolean {
        val write = record.writes.singleOrNull { it.key == operation.key } ?: return true
        if (operation.hasSendStarted != false || operation.refusal == null ||
            write.target != ResultRef("github_operation", operation.marker)
        ) {
            return false
        }
        return when (val reduced = record.reduce(ProcessCommand.WriteNotApplied(write.key, write.tries), clock.now())) {
            is ProcessReduction.Rejected -> false
            is ProcessReduction.Changed -> replace(record, reduced.record)
            is ProcessReduction.Unchanged -> true
        }
    }

    private suspend fun confirmGitHubWrite(
        record: ProcessRecord,
        key: io.aequicor.heartbeat.feature.harness.api.process.WriteKey,
        marker: String,
        result: ResultRef,
    ): Boolean {
        val write = record.writes.singleOrNull { it.key == key } ?: return true
        if (write.target != ResultRef("github_operation", marker)) return false
        return when (val confirmed = record.reduce(ProcessCommand.ConfirmWrite(key, result), clock.now())) {
            is ProcessReduction.Rejected -> false
            is ProcessReduction.Changed -> replace(record, confirmed.record)
            is ProcessReduction.Unchanged -> true
        }
    }

    override suspend fun reconcileRecipient(
        context: OwnershipTransferContext,
        checkpoint: PortableProcessCheckpoint,
    ): OwnershipLocalResult<OwnershipReconciliationReceipt> = data.processMutex.withLock {
        if (snapshot(context) == null) return@withLock OwnershipLocalResult.Pending
        val head = workspace.head(WorkspaceRef(context.binding.workspace), context.binding.identity)
        if (checkpoint.subject != null && head != checkpoint.subject) return@withLock unavailable()
        if (!importRecipient(context, checkpoint)) return@withLock OwnershipLocalResult.Pending
        if (!github.reconcileImported(context.binding.local())) return@withLock OwnershipLocalResult.Pending
        val current = snapshot(context) ?: return@withLock OwnershipLocalResult.Pending
        OwnershipLocalResult.Confirmed(OwnershipReconciliationReceipt(current, current.transfer?.checkpoint, 0))
    }

    private suspend fun importRecipient(
        context: OwnershipTransferContext,
        checkpoint: PortableProcessCheckpoint,
    ): Boolean {
        val local = context.binding.local()
        val bound = runtime.journal.binding(local) ?: return false
        val pointer = context.snapshot.transfer?.checkpoint ?: return false
        if (bound.fence != context.snapshot.fence || (bound.imported != null && bound.imported != pointer)) return false
        val existing = records().singleOrNull { it.id == checkpoint.process }
        val isReplacementAllowed = bound.imported == null && bound.previousOwner != null &&
            existing?.canReceive(checkpoint, context) == true
        val projection = checkpoint.prepareImport(
            context.binding.identity,
            context.binding.definition,
            context.binding.definitionDigest,
            if (isReplacementAllowed) null else existing?.copy(ownershipKey = null),
        )
        if (projection !is PortableProcessImport.Ready || !imported.install(local, checkpoint)) return false
        val next = projection.record.copy(ownershipKey = context.binding.key)
        return (
            existing == next || replace(
                existing,
                next,
            )
        ) && runtime.journal.bind(bound, bound.copy(imported = pointer))
    }

    private fun ProcessRecord.canReceive(
        checkpoint: PortableProcessCheckpoint,
        context: OwnershipTransferContext,
    ): Boolean {
        val isSameLifetime = incarnation == checkpoint.incarnation && createdAt == checkpoint.createdAt &&
            ownershipKey == context.binding.key && definition == context.binding.definition
        val isMonotonic = inputRevision <= checkpoint.inputRevision && nextAttempt <= checkpoint.nextAttempt &&
            nextWait <= checkpoint.nextWait && nextWriteTry <= checkpoint.nextWriteTry
        val end = phase as? ProcessPhase.Ended
        val isEndPreserved = end == null || (checkpoint.end == end.end && checkpoint.endedAt == end.at)
        return isSameLifetime && isMonotonic && inFlight == null && unresolvedWrites().isEmpty() && isEndPreserved
    }

    override suspend fun reconcileSource(
        context: OwnershipTransferContext,
    ): OwnershipLocalResult<OwnershipReconciliationReceipt> {
        val local = context.binding.local()
        val previous = runtime.journal.binding(local) ?: return unavailable()
        if (!runtime.journal.bind(
                previous,
                previous.copy(
                    fence = context.snapshot.fence,
                    previousOwner = null,
                    imported = null,
                    isSourceReconciled = previous.fence == context.snapshot.fence && previous.isSourceReconciled,
                ),
            )
        ) {
            return OwnershipLocalResult.Pending
        }
        if (!drain(context, isExporting = false)) return OwnershipLocalResult.Pending
        return data.processMutex.withLock { reconcileSourceJoined(context) }
    }

    /** Persist the cleanup receipt before Activate; every same-epoch replay is read-only thereafter. */
    private suspend fun reconcileSourceJoined(
        context: OwnershipTransferContext,
    ): OwnershipLocalResult<OwnershipReconciliationReceipt> {
        val local = context.binding.local()
        val bound = runtime.journal.binding(local)
        val current = snapshot(context)
        val record = records().singleOrNull { it.id == context.binding.process }
        val isReady = bound?.fence == context.snapshot.fence && current != null && record != null &&
            (
                bound.isSourceReconciled || (
                    record.unresolvedWrites().isEmpty() && github.reconcileNative(local) &&
                        runtime.journal.bind(bound, bound.copy(isSourceReconciled = true))
                )
            )
        return if (isReady) {
            OwnershipLocalResult.Confirmed(
                OwnershipReconciliationReceipt(current, current.transfer?.checkpoint, 0),
            )
        } else {
            OwnershipLocalResult.Pending
        }
    }

    private suspend fun snapshot(
        context: OwnershipTransferContext,
    ): io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership? {
        check(access.isActive)
        val current = (
            runtime.coordinator(
                context.binding.identity,
            ).read(context.binding.key) as? OwnershipRead.Current
        )?.record
        return current?.takeIf {
            it.fence == context.snapshot.fence && it.phase == context.snapshot.phase &&
                it.transfer?.id == context.snapshot.transfer?.id
        }
    }

    internal suspend fun replace(expected: ProcessRecord?, next: ProcessRecord): Boolean {
        val current = data.read(request.harness.id, HarnessScriptProcesses.STORAGE_KEY) { check(access.isActive) }
        val records = HarnessScriptProcesses.decodeStored(current.value)
        if (records.singleOrNull { it.id == next.id } != expected) return false
        val updated = records.filterNot { it.id == next.id } + next
        if (updated.size > ProcessLimits.PROCESSES) return false
        val result = data.write(
            request.harness.id,
            HarnessScriptProcesses.STORAGE_KEY,
            HarnessScriptProcesses.encodeStored(updated),
            current.revision,
        ) { check(access.isActive) }
        return result is io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult.Written
    }
}

private fun unavailable() = OwnershipLocalResult.Unavailable(OwnershipUnavailable.InvalidRecord)

/** Canonical reviewed bundle and selected script name; local ids/revision/workspace never enter the fingerprint. */
internal fun ownershipDefinitionDigest(request: HarnessActivationRequest): String {
    val items = request.harness.items.sortedBy { it.name.value }.map {
        val json = Json.encodeToJsonElement(HarnessItem.serializer(), it) as JsonObject
        JsonObject(json - "id")
    }
    val bundle = JsonObject(mapOf("script" to JsonPrimitive(request.item.name.value), "items" to JsonArray(items)))
    return canonicalOwnershipJson(bundle).toString().encodeUtf8().sha256().hex()
}

private fun canonicalOwnershipJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(
        value.entries.sortedBy { it.key }.associate { it.key to canonicalOwnershipJson(it.value) },
    )

    is JsonArray -> JsonArray(value.map(::canonicalOwnershipJson))

    is JsonPrimitive -> value
}
