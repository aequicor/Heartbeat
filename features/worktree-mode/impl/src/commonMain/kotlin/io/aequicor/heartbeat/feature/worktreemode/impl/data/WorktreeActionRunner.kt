package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeActionRequest
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeExpectedAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.actionPrepared
import io.aequicor.heartbeat.feature.worktreemode.api.actionVerified
import io.aequicor.heartbeat.feature.worktreemode.api.canChooseAction
import io.aequicor.heartbeat.feature.worktreemode.api.claimAction
import io.aequicor.heartbeat.feature.worktreemode.api.needsRecovery
import io.aequicor.heartbeat.feature.worktreemode.api.prepared
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Owns legacy completion action leases independently from virtual workspace lifecycle. */
internal class WorktreeActionRunner(
    private val git: WorktreeGit,
    private val builds: WorktreeBuildCoordinator,
    private val record: suspend (String) -> WorktreeRecord?,
    private val update: suspend (String, (WorktreeRecord) -> WorktreeRecord) -> WorktreeRecord?,
) {
    private val log = Log.tag("WorktreeActions")
    suspend fun prepare(command: WorktreeIntent.Public.ChooseAction, existing: WorktreeRecord): WorktreeRecord? {
        val kind = when (command.action) {
            WorktreeAction.CreatePr -> WorktreeRunKind.CreatePr
            WorktreeAction.Merge -> WorktreeRunKind.Merge
            WorktreeAction.Refine -> WorktreeRunKind.Coding
            WorktreeAction.Leave -> error("InvalidCompletionAction")
        }
        val operation = Uuid.random().toString()
        val expected = WorktreeExpectedAction(operation, kind)
        var isClaimed = false
        val claimed = update(command.chatId) {
            if (it.task.run != existing.task.run || !it.task.canChooseAction() || it.mergeLease != null) {
                it
            } else {
                isClaimed = true
                it.copy(task = it.task.claimAction(expected))
            }
        }
        return if (isClaimed && claimed != null) prepareClaimedAction(command, claimed, expected) else claimed
    }

    private suspend fun prepareClaimedAction(
        command: WorktreeIntent.Public.ChooseAction,
        claimed: WorktreeRecord,
        expected: WorktreeExpectedAction,
    ): WorktreeRecord? = try {
        val current = if (expected.kind == WorktreeRunKind.Merge) acquireActionLease(claimed, expected) else claimed
        when {
            current == null -> null
            !current.ownsAction(expected) -> abandonAction(current, expected)
            else -> publishAction(command, current, expected)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val current = record(command.chatId)
        if (current == null || current.ownsAction(expected)) throw error
        log.w(IllegalStateException("StaleActionPreparationFailed (${error::class.simpleName.orEmpty()})")) {
            "Discard action preparation failure after its claim was superseded"
        }
        abandonAction(current, expected)
    }

    private suspend fun acquireActionLease(
        claimed: WorktreeRecord,
        expected: WorktreeExpectedAction,
    ): WorktreeRecord? {
        val target = git.mergePreflight(claimed)
        val lease = git.mergeLease(claimed, expected.operation)
        val leased = update(claimed.task.chatId) {
            if (it.ownsAction(expected)) it.copy(mergeLease = lease, mergeTargetCommit = target) else it
        }
        if (leased?.ownsAction(expected) == true) builds.acquireLease(lease)
        return record(claimed.task.chatId)
    }

    private suspend fun publishAction(
        command: WorktreeIntent.Public.ChooseAction,
        current: WorktreeRecord,
        expected: WorktreeExpectedAction,
    ): WorktreeRecord? {
        val prompt = if (expected.kind == WorktreeRunKind.Coding) {
            checkNotNull(command.refinement)
        } else {
            git.actionPrompt(current, merge = expected.kind == WorktreeRunKind.Merge)
        }
        val request = WorktreeActionRequest(expected.operation, expected.kind, prompt)
        val prepared = update(command.chatId) { it.copy(task = it.task.actionPrepared(request)) }
        return when {
            prepared == null -> null
            prepared.ownsAction(expected) -> prepared
            else -> abandonAction(prepared, expected)
        }
    }

    private fun WorktreeRecord.ownsAction(expected: WorktreeExpectedAction): Boolean =
        task.phase == WorktreePhase.ActionWorking && task.expectedAction == expected

    private suspend fun abandonAction(record: WorktreeRecord, expected: WorktreeExpectedAction): WorktreeRecord {
        val isNativeRunning = record.task.run?.let {
            it.request.value == expected.operation && it.outcome == null
        } == true
        return if (record.mergeLease?.id == expected.operation && !isNativeRunning) releaseMerge(record) else record
    }

    suspend fun releaseMerge(record: WorktreeRecord): WorktreeRecord {
        val lease = record.mergeLease ?: return record
        if (!builds.releaseLease(lease)) {
            return update(record.task.chatId) {
                it.copy(task = it.task.needsRecovery("MergeLeaseReleasePending"))
            } ?: record
        }
        return update(record.task.chatId) {
            if (it.mergeLease?.id == lease.id) it.copy(mergeLease = null) else it
        } ?: record
    }

    suspend fun verify(record: WorktreeRecord): WorktreeRecord {
        val run = record.task.run ?: return record
        if (run.kind == WorktreeRunKind.Coding || run.outcome != TurnOutcome.Completed || !run.isCompletionSignaled) {
            return record
        }
        val isVerified = git.verifyAction(record, run.kind == WorktreeRunKind.Merge, run.pullRequestUrl)
        val url = if (run.kind == WorktreeRunKind.CreatePr) run.pullRequestUrl else null
        return update(record.task.chatId) {
            if (it.task.run != run) return@update it
            it.copy(
                task = if (isVerified) it.task.actionVerified(url) else it.task.needsRecovery("ActionResultUnverified"),
            )
        } ?: record
    }
}
