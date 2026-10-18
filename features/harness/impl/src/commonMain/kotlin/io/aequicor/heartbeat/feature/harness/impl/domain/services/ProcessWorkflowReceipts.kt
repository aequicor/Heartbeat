package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.reduce
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.ownershipIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Instant

/**
 * Trusted native owner records cleanup before releasing journal retention. CAS retries only the pure reduction;
 * a concurrent user/script edit is preserved. This path grants no script access to another harness's data.
 */
internal suspend fun HarnessDataStorage.confirmProcessWorkflow(owner: HarnessId, run: WorkflowRun, now: Instant) {
    try {
        persistWorkflowReceipt(owner, run, now)
        run.ownershipIntent()?.let { intent ->
            check(ownership?.complete(intent) == true) { "Workflow ownership completion is unavailable" }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("Workflow cleanup receipt could not be stored")
        receiptLog.w(failure) { "Process receipt storage failed (${error::class.simpleName.orEmpty()})" }
        throw failure
    }
}

private suspend fun HarnessDataStorage.persistWorkflowReceipt(owner: HarnessId, run: WorkflowRun, now: Instant) {
    check(run.harness == owner && run.status != WorkflowStatus.Running) { "Workflow cleanup is not confirmed" }
    val binding = checkNotNull(run.process)
    val command = ProcessCommand.SettleWorkflow(binding.key, ProcessWorkflow(run.id, binding.script, binding.digest))
    var current = read(owner, HarnessScriptProcesses.STORAGE_KEY) {}
    var isStored = false
    while (!isStored) {
        currentCoroutineContext().ensureActive()
        val records = HarnessScriptProcesses.decodeStored(current.value)
        val record = records.firstOrNull { it.id == binding.key.process && it.incarnation == binding.key.incarnation }
        if (record == null || run.isPrunedFrom(record)) break
        val reduction = record.reduce(command, now)
        when (reduction) {
            is ProcessReduction.Rejected -> error("Workflow cleanup receipt conflicts with the process")

            is ProcessReduction.Unchanged -> isStored = true

            is ProcessReduction.Changed -> {
                when (val result = writeReceipt(owner, records, record, reduction.record, current.revision)) {
                    is ScriptDataWriteResult.Written -> isStored = true
                    is ScriptDataWriteResult.Conflict -> current = result.current
                }
            }
        }
    }
}

private val receiptLog = Log.tag("HarnessProcessReceipt")

private fun WorkflowRun.isPrunedFrom(record: io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord): Boolean {
    val binding = checkNotNull(process)
    val isMissing = record.inFlight?.number != binding.key.attempt &&
        record.history.none { it.attempt.number == binding.key.attempt }
    return binding.ownership != null && isMissing && record.nextAttempt > binding.key.attempt
}

private suspend fun HarnessDataStorage.writeReceipt(
    owner: HarnessId,
    records: List<io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord>,
    previous: io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord,
    next: io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord,
    revision: Long,
): ScriptDataWriteResult {
    val updated = records.map { if (it.id == previous.id) next else it }
    return write(owner, HarnessScriptProcesses.STORAGE_KEY, HarnessScriptProcesses.encodeStored(updated), revision) {}
}
