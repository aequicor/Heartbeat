package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository

/**
 * Explicit cooperative handover through the host's native decision panel. Scripts can request and observe;
 * they cannot approve, attest native termination, choose their host identity or supply import receipts.
 * Call advance until Active/Exported or an explicit unresolved result. No timeout performs takeover.
 */
public interface ScriptProcessOwnership {
    /** Persistent profile host code to give the source; null when enrollment is disabled/unavailable. */
    public suspend fun host(): ExecutorHostId?

    /** Enrolls a fresh idle local process against a verified external task, after native user consent. */
    public suspend fun enroll(
        process: ProcessId,
        repository: GitHubRepository,
        kind: GitHubTaskKind,
        number: Long,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult

    /** Closes new admissions for this exact source and named recipient after native user consent. */
    public suspend fun prepare(
        process: ProcessId,
        target: ExecutorHostId,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult

    /** Claims only an exported task addressed to this host; imports no approvals and starts no executor. */
    public suspend fun claim(
        repository: GitHubRepository,
        kind: GitHubTaskKind,
        number: Long,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult

    /** Advances at most one durable phase; native/external uncertainty keeps it pending. */
    public suspend fun advance(process: ProcessId, workspace: WorkspaceRef): ScriptOwnershipResult

    /** Cancels an unclaimed transfer after native consent; epoch rotation still requires reconciliation. */
    public suspend fun cancel(process: ProcessId, workspace: WorkspaceRef): ScriptOwnershipResult

    /** Fresh observation only, never permission to execute. */
    public suspend fun status(process: ProcessId): ScriptOwnershipResult
}

/** A snapshot, native decision request, pending drain or explicit unavailability; no result is a capability. */
public sealed interface ScriptOwnershipResult {
    /** Current shared coordinator record, which the caller must not cache as execution authority. */
    public data class Current(val ownership: ProcessOwnership) : ScriptOwnershipResult

    /** Review the exact request in the native process-decision panel, then repeat the operation. */
    public data class Decision(val id: String) : ScriptOwnershipResult

    /** In-flight work or external facts are not yet positively reconciled. */
    public data object Pending : ScriptOwnershipResult

    /** This snapshot/operation is not permitted; no takeover or dispatch was performed. */
    public data object Refused : ScriptOwnershipResult

    /** Disabled, unsupported or uncertain storage/network; absence of an owner is never inferred. */
    public data object Unavailable : ScriptOwnershipResult
}

/** Compatibility for script hosts without the distributed ownership runtime. */
public object UnavailableScriptProcessOwnership : ScriptProcessOwnership {
    override suspend fun host(): ExecutorHostId? = null
    override suspend fun enroll(
        process: ProcessId,
        repository: GitHubRepository,
        kind: GitHubTaskKind,
        number: Long,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = ScriptOwnershipResult.Unavailable
    override suspend fun prepare(
        process: ProcessId,
        target: ExecutorHostId,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = ScriptOwnershipResult.Unavailable
    override suspend fun claim(
        repository: GitHubRepository,
        kind: GitHubTaskKind,
        number: Long,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = ScriptOwnershipResult.Unavailable
    override suspend fun advance(process: ProcessId, workspace: WorkspaceRef): ScriptOwnershipResult =
        ScriptOwnershipResult.Unavailable
    override suspend fun cancel(process: ProcessId, workspace: WorkspaceRef): ScriptOwnershipResult =
        ScriptOwnershipResult.Unavailable
    override suspend fun status(process: ProcessId): ScriptOwnershipResult = ScriptOwnershipResult.Unavailable
}
