package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal

private val sealLog = Log.tag("CheckoutSeal")

/**
 * Captures the files of an isolated checkout as an immutable commit while no native turn works in it, and hands the
 * outcome to [commit], which records it in the task. A failure is thrown and reaches the task through the journal as
 * the seal's failure. The caller holds the task's run-preparation lock, so a new turn cannot start between the check
 * and the capture; the capture never touches the branch, the index or a file of the checkout.
 */
internal suspend fun sealCheckout(
    git: WorktreeGit,
    command: WorktreeIntent.Public.Seal,
    current: suspend () -> WorktreeRecord?,
    commit: suspend (WorktreeSeal) -> WorktreeRecord?,
): WorktreeRecord? {
    check(git.isAvailable) { "WorktreeModeUnavailable" }
    val record = current() ?: return null
    check(record.task.isIsolated && record.task.executionWorkspace != null) { "SealUnavailable" }
    check(record.task.run?.let { it.outcome != null } != false) { "TurnActive" }
    if (record.task.pinnedBaseCommit != null) {
        check(record.resultProofs.size < MAX_RESULT_PROOFS || command.operation in record.resultProofs) {
            "ResultProofCapacity"
        }
    }
    check(git.exists(record)) { "WorktreeUnavailable" }
    val captured = git.seal(record, command.operation)
    sealLog.i { "Checkout sealed operation=${command.operation}" }
    return commit(WorktreeSeal(command.operation, captured))
}

private const val MAX_RESULT_PROOFS = 64
