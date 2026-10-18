package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWait
import io.aequicor.heartbeat.feature.harness.api.process.waitKey

/**
 * Reconciles a stored wait without starting a workflow or keeping an agent alive. Call from the owning item's
 * Activated handler and from event/timer handlers. [isSatisfied] queries actual external state, so activation
 * recovers a missed event even days after the previous workflow expired. False leaves the wait parked; a deadline
 * alone is not proof of external success. Bound external IO by the callback timeout.
 *
 * The predicate runs once, outside the storage transaction. True submits an atomic snapshot-checked resume;
 * a concurrent revision/SHA change refuses it and a late result never ends a newer wait or process incarnation.
 * Reconcile again on StaleRevision. The host does not infer business success from an untrusted event payload.
 */
public suspend fun ScriptProcesses.reconcileWait(
    id: ProcessId,
    isSatisfied: suspend (ProcessRecord, ProcessWait) -> Boolean,
): ProcessReduction {
    val record = get(id) ?: return ProcessReduction.Rejected(ProcessRejection.UnknownProcess)
    val key = record.waitKey() ?: return ProcessReduction.Unchanged(record)
    val wait = (record.phase as ProcessPhase.Waiting).wait
    return if (isSatisfied(record, wait)) {
        apply(id, ProcessCommand.ResumeChecked(key))
    } else {
        ProcessReduction.Unchanged(record)
    }
}
