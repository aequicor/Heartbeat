package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Native identity scoped to one engine and history store, never to an authentication binding.
 * Logical Heartbeat conversations may reference multiple native segments when changing engines;
 * such orchestration owns its own identity and never rewrites this native reference.
 */
@Serializable
public data class SessionRef(val engine: EngineId, val source: SessionSourceId, val nativeId: String) {
    init {
        require(nativeId.isNotBlank())
    }
}

/** Non-secret reference to a configured host/store. Native paths and host credentials stay in adapters. */
@Serializable
public data class SessionSource(val id: SessionSourceId, val engine: EngineId, val label: String)

/** Origin is unknown if no reliable creation provenance was persisted. */
@Serializable
public enum class SessionOrigin { Heartbeat, External, Unknown }

/** Native creation/update times may be unavailable and must not be fabricated. */
@Serializable
public data class SessionTimes(val createdAt: Instant? = null, val updatedAt: Instant? = null)

/** Persistent metadata independent of a running process. Null lastRoute means unknown, not the current default. */
@Serializable
public data class SessionSummary(
    val ref: SessionRef,
    val title: String? = null,
    val workspace: WorkspaceRef? = null,
    val origin: SessionOrigin = SessionOrigin.Unknown,
    val times: SessionTimes = SessionTimes(),
    val isArchived: Boolean = false,
    val lastRoute: ExecutionRoute? = null,
    val observation: Observation = Observation(),
    val features: Set<EngineFeatureId> = emptySet(),
)

/** Explicit archive selection, avoiding hidden native defaults when requesting all sessions. */
@Serializable
public enum class ArchiveFilter { All, Active, Archived }

/** Stable ordering requires SessionRef as a tie-breaker and snapshot invalidation on reordering. */
@Serializable
public enum class SessionOrder { RecentlyUpdated, RecentlyCreated }

/** Query scope is part of cursor identity. Empty engine/source sets include all configured sources in this profile. */
@Serializable
public data class SessionQuery(
    val engines: Set<EngineId> = emptySet(),
    val sources: Set<SessionSourceId> = emptySet(),
    val workspace: WorkspaceRef? = null,
    val search: String? = null,
    val archive: ArchiveFilter = ArchiveFilter.All,
    val order: SessionOrder = SessionOrder.RecentlyUpdated,
)

/** Explicit creation request; credential compatibility and revision are checked before execution. */
@Serializable
public data class CreateSessionRequest(val target: EngineTarget, val workspace: WorkspaceRef? = null)

/** Explicit resume route. Unknown historical authentication requires resolution/confirmation before this call. */
@Serializable
public data class ResumeSessionRequest(val target: EngineTarget, val workspace: WorkspaceRef? = null)

/** Accepted request correlation and multimodal content. RequestId is not a promise of native deduplication. */
@Serializable
public data class PromptRequest(val id: RequestId, val parts: List<ContentPart>) {
    init {
        require(parts.isNotEmpty())
    }
    override fun toString(): String = "PromptRequest(id=$id, parts=${parts.size})"
}

/** Final outcome, recorded once per accepted turn. */
@Serializable
public sealed interface TurnOutcome {
    /** The engine completed the turn. */
    @Serializable
    public data object Completed : TurnOutcome

    /** The engine confirmed cancellation; local task cancellation alone is insufficient. */
    @Serializable
    public data object Cancelled : TurnOutcome

    /**
     * Authoritative reconciliation confirms the turn is no longer active, but its native outcome is unavailable.
     * This is neither success nor confirmed failure and must not trigger an automatic retry.
     * Later history reads may recover the exact result; this handle emits its terminal notification only once.
     */
    @Serializable
    public data object Unknown : TurnOutcome

    /** Failure after request acceptance. */
    @Serializable
    public data class Failed(val failure: EngineFailure) : TurnOutcome
}

/** Known turn boundary. Historical adapters leave item turn references null when boundaries are unknown. */
@Serializable
public data class Turn(
    val id: TurnId,
    val request: RequestId?,
    val target: EngineTarget,
    val outcome: TurnOutcome? = null,
    /** Acknowledged permission ids retained until the turn ends, including across reconciliation. */
    val resolvedPermissions: Set<PermissionRequestId> = emptySet(),
)

/** Engine-offered decision; identifiers are validated against the outstanding request before delivery. */
@Serializable
public data class PermissionOption(val id: PermissionOptionId, val title: String)

/** Pending action is authoritative state; replaying a historical notification never makes it pending again. */
@Serializable
public data class PermissionRequest(
    val id: PermissionRequestId,
    val turn: TurnId,
    val title: String,
    val options: List<PermissionOption>,
) {
    init {
        require(options.isNotEmpty())
        require(options.map { it.id }.distinct().size == options.size)
    }
}

/** User response correlated to the original request and turn. */
@Serializable
public data class PermissionDecision(val turn: TurnId, val request: PermissionRequestId, val option: PermissionOptionId)
