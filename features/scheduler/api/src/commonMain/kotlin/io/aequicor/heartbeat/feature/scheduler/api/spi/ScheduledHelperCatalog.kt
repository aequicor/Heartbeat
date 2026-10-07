package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadataLimits

/** Read-only durable helper identities, independent of native execution and acceptance receipts. */
public interface ScheduledHelperCatalog {
    /** Durable metadata of a marked helper owned by this host; null for ordinary or unknown chats. */
    public suspend fun helperMetadata(helper: HelperId): HelperMetadata? = null

    /**
     * Exact reverse lookup in durable helper records; null for ordinary/unknown sessions. The host persists the
     * native ref before invoking prompt hooks, so lookup does not depend on an acceptance ACK. Never starts IO
     * against a native engine. Multiple matching records must fail rather than choose an arbitrary owner.
     */
    public suspend fun helperMetadata(session: SessionRef): HelperMetadata? = null

    /**
     * Durable owner page ordered by HelperId.value, strictly after [after], with at most [limit] entries.
     * Follows HelperAgents.owned semantics, including historical records and a 1..128 page-size bound.
     * An unsupported host returns an empty page; it must not infer ownership from native ids or titles.
     */
    public suspend fun ownedHelpers(
        owner: ActionId,
        after: HelperId? = null,
        limit: Int = HelperMetadataLimits.DEFAULT_PAGE_SIZE,
    ): List<HelperMetadata> = emptyList()
}
