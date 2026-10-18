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
            ?: return
        val reduction = record.reduce(command, now)
        when (reduction) {
            is ProcessReduction.Rejected -> error("Workflow cleanup receipt conflicts with the process")

            is ProcessReduction.Unchanged -> return

            is ProcessReduction.Changed -> {
                val updated = records.map { if (it.id == record.id) reduction.record else it }
                val next = HarnessScriptProcesses.encodeStored(updated)
                when (val result = write(owner, HarnessScriptProcesses.STORAGE_KEY, next, current.revision) {}) {
                    is ScriptDataWriteResult.Written -> isStored = true
                    is ScriptDataWriteResult.Conflict -> current = result.current
                }
            }
        }
    }
}

private val receiptLog = Log.tag("HarnessProcessReceipt")
