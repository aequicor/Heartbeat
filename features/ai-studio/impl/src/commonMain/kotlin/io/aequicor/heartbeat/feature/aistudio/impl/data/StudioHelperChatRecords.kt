package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadataLimits
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The marker survives native reopen and identifies parentless helpers too. [parentChatId] groups the sidebar;
 * [parentSession] retains the caller identity used to authorize lease recovery, even if its native ref changes.
 */
@Serializable
internal data class StudioHelperIdentity(
    val owner: ActionId,
    val parentChatId: String?,
    val parentSession: SessionRef?,
    val trustCap: TrustLevel,
)

/** Shares the chat repository's write lock with helper creation, so concurrent ordinary edits are preserved. */
internal interface StudioHelperChatWriter {
    suspend fun saveHelper(record: StudioChatRecord)
}

/** Studio-owned storage operations; helper creation shares the repository's chat write lock. */
internal interface StudioHelperChatRecords {
    /** Atomically persists an empty helper, without creating or sending to a native session. */
    suspend fun createHelper(request: HelperCreateRequest): HelperId

    /** Ordinary chats never carry helper ownership. */
    suspend fun helperMetadata(helper: HelperId): HelperMetadata?

    /** Exact persisted native reference; conflicting records fail instead of choosing one owner. */
    suspend fun helperMetadata(session: SessionRef): HelperMetadata?

    /** Bounded live page of durable identities, sorted by helper id, strictly after the cursor. */
    suspend fun ownedHelpers(
        owner: ActionId,
        after: HelperId? = null,
        limit: Int = HelperMetadataLimits.DEFAULT_PAGE_SIZE,
    ): List<HelperMetadata>

    /** Reads the persisted marker rather than inferring ownership from an active run. */
    suspend fun isHelper(session: SessionRef): Boolean
}

/** A helper borrows the parent's checkout, never its worktree provisioning or action ownership. */
internal fun newStudioHelperRecord(
    id: String,
    at: Instant,
    request: HelperCreateRequest,
    parent: StudioChatRecord?,
): StudioChatRecord {
    require((parent == null) == (request.parent == null) && parent?.ref == request.parent) {
        "The helper parent does not match its caller"
    }
    check(parent?.worktreeTaskId == null || parent.executionWorkspace != null) {
        "The helper parent's worktree is not ready"
    }
    return StudioChatRecord(
        id,
        request.title,
        at,
        target = request.target,
        projectId = parent?.projectId,
        executionWorkspace = if (parent == null) request.workspace else parent.resolvedExecutionWorkspace(),
        helper = StudioHelperIdentity(
            request.owner,
            parent?.id,
            request.parent,
            if (parent == null) TrustLevel.Ask else request.trustCap,
        ),
    )
}

/** Execution identity also exists for a helper outside the project's sidebar grouping. */
internal fun StudioChatRecord.resolvedExecutionWorkspace(): WorkspaceRef? =
    executionWorkspace ?: projectId?.let(::WorkspaceRef)
