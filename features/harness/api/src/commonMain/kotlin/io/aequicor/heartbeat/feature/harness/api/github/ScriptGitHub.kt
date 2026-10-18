package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Exact working copy and stage attempt for a host-owned GitHub decision. No permission is carried here. */
@Serializable
public data class GitHubActionContext(val workspace: WorkspaceRef, val attempt: ProcessWorkflowKey) {
    override fun toString(): String = "GitHubActionContext(***)"
}

/** An explicit page; an opaque project cursor and a REST page are never interchangeable. */
public data class GitHubSnapshot(
    val entries: List<GitHubEntity> = emptyList(),
    val nextPage: Int? = null,
    val nextCursor: String? = null,
    val commit: SubjectSha? = null,
) {
    override fun toString(): String = "GitHubSnapshot(entries=${entries.size})"
}

/** Read failure categories exclude server bodies, credential values and transport diagnostics. */
public enum class GitHubFailure {
    /** Feature toggle is disabled. */
    Disabled,

    /** Platform has no gh adapter. */
    Unsupported,

    /** Existing gh login is absent or lacks access. */
    Access,

    /** Object is not visible; this does not prove a previous write did not happen. */
    NotFound,

    /** Server asks the caller to back off. */
    RateLimited,

    /** Offline, timeout, process or server failure. */
    Unavailable,

    /** Bounded response could not be decoded completely. */
    InvalidResponse,
}

/** Outcome of one bounded read; retry is permitted only at [Failed.retryAt] or later. */
public sealed interface GitHubReadResult {
    /** Complete page; [snapshot] still may have a next page. */
    public data class Read(val snapshot: GitHubSnapshot) : GitHubReadResult

    /** No usable observation; retain the old state. */
    public data class Failed(val reason: GitHubFailure, val retryAt: Instant? = null) : GitHubReadResult
}

/** Outcome of a durable operation; Unknown never authorizes another external call. */
public sealed interface GitHubWriteResult {
    /** Native host decision status; return and park the process rather than holding an executor while waiting. */
    public data class Decision(val id: String, val phase: ProcessDecisionPhase) : GitHubWriteResult

    /** Durable server identity. The same key replays this result even after local process journal eviction. */
    public data class Confirmed(val result: ResultRef) : GitHubWriteResult

    /** Reserved or sent; call reconcile, never retry with a fresh key to evade an unknown outcome. */
    public data object Unknown : GitHubWriteResult

    /** No external call was made for this invocation. */
    public data class Refused(val reason: GitHubWriteRefusal) : GitHubWriteResult
}

/** Fail-closed refusal categories. */
public enum class GitHubWriteRefusal {
    /** Toggle/platform/read admission unavailable. */
    Unavailable,

    /** Process missing, stale input revision or changed incarnation. */
    StaleProcess,

    /** Write key is bound to a different request. */
    KeyConflict,

    /** Durable registry is full; confirmed and unresolved keys are never evicted. */
    Quota,

    /** Missing process grant or independent host admission. */
    NotAuthorized,

    /** Current remote head or tag differs from the request. */
    SubjectConflict,
}

/**
 * GitHub services of one approved activation. Every call checks activation, hook origin and feature toggle.
 * Existing gh authentication is used on Desktop; credentials are never returned to scripts. Reads are explicit,
 * bounded polling building blocks: activation handlers reconcile pending operations, and timers choose polling
 * intervals respecting retryAt. No public webhook server is required.
 *
 * Writes first reserve a permanent key in protected harness data (`hb_github`), then BeginWrite, then call GitHub,
 * then ConfirmWrite. A crash at any point after reservation permits reconciliation only. The registry shares the
 * ScriptData quota and never evicts keys. Namespace deletion is an explicit destructive reset requiring manual
 * reconciliation. Merge and release require separate host-owned authority; script ProcessAuthority grants alone
 * are insufficient. Server text and correlation markers never confer authority.
 */
public interface ScriptGitHub {
    /** Read a single page; maximum 50 records. Failed reads never replace observed state. */
    public suspend fun read(repository: GitHubRepository, query: GitHubQuery): GitHubReadResult

    /**
     * One immutable operation at the exact process input revision; key reuse must carry the same request.
     * Merge, release and dispatch require [context] and a native host decision for the complete typed payload.
     * Pending/denied/revoked decisions reserve no external operation. After approval repeat the same request;
     * Executing/Completed with a missing external registry returns Unknown rather than resending.
     */
    public suspend fun write(
        process: ProcessId,
        inputRevision: Long,
        key: WriteKey,
        repository: GitHubRepository,
        change: GitHubChange,
        context: GitHubActionContext? = null,
    ): GitHubWriteResult

    /** Read and reconcile the reserved operation; never sends a write, even if no matching object is found. */
    public suspend fun reconcile(process: ProcessId, key: WriteKey): GitHubWriteResult
}
