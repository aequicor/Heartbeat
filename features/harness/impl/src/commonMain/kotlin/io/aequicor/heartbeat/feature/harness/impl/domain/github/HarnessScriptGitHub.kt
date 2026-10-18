package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.github.GitHubActionContext
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubFailure
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubReadResult
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.github.ScriptGitHub
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Admission for effects that may publish, integrate or run arbitrary workflow code. The host supplies this port;
 * scripts cannot implement it. Preparation never consumes consent; claim is called at the actual dispatch boundary.
 */
internal fun interface GitHubHostAuthority {
    suspend fun allows(operation: GitHubOperation): Boolean

    suspend fun prepare(operation: GitHubOperation): GitHubAuthorization = if (allows(operation)) {
        GitHubAuthorization.Allowed()
    } else {
        GitHubAuthorization.Stopped(refused(GitHubWriteRefusal.NotAuthorized))
    }

    suspend fun claim(operation: GitHubOperation): Boolean = allows(operation)

    suspend fun complete(operation: GitHubOperation) = Unit
}

/** Host-only preparation result; a decision identity is durable data, never a capability passed to author code. */
internal sealed interface GitHubAuthorization {
    data class Allowed(val decisionId: String? = null) : GitHubAuthorization
    data class Stopped(val result: GitHubWriteResult) : GitHubAuthorization
}

/** Durable external writes. Reservation is atomic; only its creator may send once, every replay reads evidence. */
internal class HarnessScriptGitHub(
    private val access: HarnessInstanceAccess,
    private val origins: HarnessCallOrigins,
    private val processes: ScriptProcesses,
    private val registry: GitHubRegistry,
    private val host: GitHubHost,
    private val processGate: Mutex,
) : ScriptGitHub {
    private val api get() = host.api
    private val authority get() = host.authority
    private suspend fun isEnabled(): Boolean = host.isEnabled()
    private val reconciliation = GitHubReconciliation(api)
    private val log = Log.tag("HarnessScriptGitHub")

    override suspend fun read(repository: GitHubRepository, query: GitHubQuery): GitHubReadResult = activated {
        if (!isEnabled()) return@activated GitHubReadResult.Failed(GitHubFailure.Disabled)
        if (!api.isAvailable) return@activated GitHubReadResult.Failed(GitHubFailure.Unsupported)
        api.read(repository, query)
    }

    override suspend fun write(
        process: ProcessId,
        inputRevision: Long,
        key: WriteKey,
        repository: GitHubRepository,
        change: GitHubChange,
        context: GitHubActionContext?,
    ): GitHubWriteResult = activated {
        require(inputRevision >= 0) { "Invalid input revision" }
        val snapshot = if (change is GitHubChange.Dispatch) {
            change.copy(
                inputs = change.inputs.entries.sortedBy { it.key }.associate { it.key to it.value },
            )
        } else {
            change
        }
        snapshot.validate()
        if (!isEnabled() || !api.isAvailable) return@activated refused(GitHubWriteRefusal.Unavailable)
        val record = processes.get(process) ?: return@activated refused(GitHubWriteRefusal.StaleProcess)
        val candidate = GitHubOperation(
            process,
            record.incarnation,
            inputRevision,
            key,
            repository,
            snapshot,
            context = context,
        )
        val existing = registry.find(process, record.incarnation, key)
        if (existing != null) return@activated existing(existing, candidate)
        fresh(record, candidate)
    }

    private suspend fun existing(operation: GitHubOperation, candidate: GitHubOperation): GitHubWriteResult =
        if (operation.sameRequest(candidate)) replay(operation) else refused(GitHubWriteRefusal.KeyConflict)

    private suspend fun fresh(record: ProcessRecord, candidate: GitHubOperation): GitHubWriteResult {
        if (record.inputRevision != candidate.inputRevision) return refused(GitHubWriteRefusal.StaleProcess)
        if (!hasGrant(record, candidate)) return refused(GitHubWriteRefusal.NotAuthorized)
        val authorization = if (candidate.change.isOrdinary()) {
            GitHubAuthorization.Allowed()
        } else {
            authority.prepare(
                candidate,
            )
        }
        if (authorization is GitHubAuthorization.Stopped) return authorization.result
        val admitted = candidate.copy(decisionId = (authorization as GitHubAuthorization.Allowed).decisionId)
        // A pre-existing local key belongs to an operation outside this registry. Never infer it is ours.
        if (record.writes.any { it.key == candidate.key }) return refused(GitHubWriteRefusal.KeyConflict)
        return when (val reservation = registry.reserve(admitted)) {
            GitHubReservation.Full -> refused(GitHubWriteRefusal.Quota)

            is GitHubReservation.Existing -> if (reservation.operation.sameRequest(candidate)) {
                replay(reservation.operation)
            } else {
                refused(GitHubWriteRefusal.KeyConflict)
            }

            is GitHubReservation.Reserved -> send(reservation.operation)
        }
    }

    override suspend fun reconcile(process: ProcessId, key: WriteKey): GitHubWriteResult = activated {
        if (!isEnabled() || !api.isAvailable) return@activated refused(GitHubWriteRefusal.Unavailable)
        val record = processes.get(process) ?: return@activated refused(GitHubWriteRefusal.StaleProcess)
        val operation = registry.find(process, record.incarnation, key)
            ?: return@activated refused(GitHubWriteRefusal.KeyConflict)
        replay(operation)
    }

    private suspend fun send(operation: GitHubOperation): GitHubWriteResult {
        val record = processes.get(operation.process)
        if (record == null || record.incarnation != operation.incarnation ||
            record.inputRevision != operation.inputRevision
        ) {
            return reject(operation, GitHubWriteRefusal.StaleProcess)
        }
        if (!hasGrant(record, operation)) return reject(operation, GitHubWriteRefusal.NotAuthorized)
        val begun = processes.apply(
            operation.process,
            ProcessCommand.BeginWrite(
                operation.key,
                operation.change.authority(),
                ResultRef("github_operation", operation.marker),
            ),
        )
        if (begun !is ProcessReduction.Changed) return reject(operation, GitHubWriteRefusal.KeyConflict)
        val write = begun.record.writes.single { it.key == operation.key }
        processes.apply(operation.process, ProcessCommand.MarkWriteUnknown(operation.key, write.tries))
        // Any failure from this point retains the permanent reservation. Cancellation is never proof of no effect.
        if (!reconciliation.preflight(operation)) return GitHubWriteResult.Unknown
        val response = processGate.withLock {
            val latest = processes.get(operation.process)
            if (latest == null || !latest.isCurrent(operation)) return@withLock GitHubResponse(0)
            api.call(operation.change.request(operation.repository, operation.marker)) {
                admit(latest, operation)
            }
        }
        val result = guardedEvidence { reconciliation.response(operation, response) }
        return if (result == null) GitHubWriteResult.Unknown else confirm(operation, result)
    }

    private suspend fun admit(record: ProcessRecord, operation: GitHubOperation): Boolean {
        if (!isEnabled() || !access.isActive || !hasGrant(record, operation)) return false
        val isClaimed = operation.change.isOrdinary() || authority.claim(operation)
        return isClaimed && access.isActive && isEnabled()
    }

    private suspend fun replay(operation: GitHubOperation): GitHubWriteResult = when {
        operation.refusal != null -> refused(operation.refusal)

        operation.result != null -> confirm(operation, operation.result)

        else -> {
            val result = guardedEvidence { reconciliation.find(operation) }
            if (result == null) GitHubWriteResult.Unknown else confirm(operation, result)
        }
    }

    private suspend fun confirm(operation: GitHubOperation, result: ResultRef): GitHubWriteResult {
        // Persist the result first: a crash before ConfirmWrite replays the same immutable external identity.
        registry.settle(operation, result = result)
        if (!operation.change.isOrdinary()) authority.complete(operation)
        val record = processes.get(operation.process)
        val local = record?.takeIf { it.incarnation == operation.incarnation }?.writes?.firstOrNull {
            it.key ==
                operation.key
        }
        if (local != null && (local.isUnresolved || local.phase == WritePhase.Confirmed(result))) {
            val confirmed = processes.apply(operation.process, ProcessCommand.ConfirmWrite(operation.key, result))
            check(confirmed !is ProcessReduction.Rejected) { "GitHub local confirmation was refused" }
        }
        return GitHubWriteResult.Confirmed(result)
    }

    private suspend fun reject(operation: GitHubOperation, reason: GitHubWriteRefusal): GitHubWriteResult {
        registry.settle(operation, refusal = reason)
        return refused(reason)
    }

    private fun hasGrant(record: ProcessRecord, operation: GitHubOperation): Boolean =
        operation.change.authority() in record.grants

    private suspend fun guardedEvidence(block: suspend () -> ResultRef?): ResultRef? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("GitHub evidence is incomplete")
        log.w(failure) { "GitHub evidence rejected (${error::class.simpleName.orEmpty()})" }
        null
    }

    private suspend fun <T> activated(block: suspend () -> T): T {
        check(access.isActive) { "GitHub requires the published activation" }
        check(!origins.current().isHookRestricted) { "Hooks cannot access GitHub" }
        val caller = currentCoroutineContext().job
        val cancellation = access.scope.coroutineContext.job.invokeOnCompletion { caller.cancel() }
        return try {
            block()
        } finally {
            cancellation.dispose()
        }
    }
}

private fun refused(reason: GitHubWriteRefusal): GitHubWriteResult = GitHubWriteResult.Refused(reason)

internal fun GitHubChange.authority(): ProcessAuthority = when (this) {
    is GitHubChange.Merge -> ProcessAuthority.Merge

    is GitHubChange.CreateRelease, is GitHubChange.Dispatch -> ProcessAuthority.Release

    is GitHubChange.CreateIssue, is GitHubChange.CreatePull, is GitHubChange.Comment,
    is GitHubChange.AddSubIssue, is GitHubChange.AddProjectItem,
    -> ProcessAuthority.Write
}

private fun GitHubChange.isOrdinary(): Boolean = when (this) {
    is GitHubChange.Merge, is GitHubChange.CreateRelease, is GitHubChange.Dispatch -> false

    is GitHubChange.CreateIssue, is GitHubChange.CreatePull, is GitHubChange.Comment,
    is GitHubChange.AddSubIssue, is GitHubChange.AddProjectItem,
    -> true
}

private fun ProcessRecord.isCurrent(operation: GitHubOperation): Boolean {
    if (incarnation != operation.incarnation || inputRevision != operation.inputRevision ||
        phase is ProcessPhase.Ended
    ) {
        return false
    }
    val local = writes.firstOrNull { it.key == operation.key } ?: return false
    return local.isUnresolved && local.target == ResultRef("github_operation", operation.marker)
}

/** Profile services shared by each activation; scripts never receive these authority/transport handles. */
internal data class GitHubHost(
    val api: GitHubApi,
    val authority: GitHubHostAuthority,
    val isEnabled: suspend () -> Boolean,
)
