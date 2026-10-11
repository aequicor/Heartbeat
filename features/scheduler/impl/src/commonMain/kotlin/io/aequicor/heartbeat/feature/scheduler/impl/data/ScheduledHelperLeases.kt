package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease

/** Internal handoff of a scheduled slot to the profile's shared helper lease registry. */
internal interface ScheduledHelperLeases : HelperAgents {
    /** Caller owns the scheduled reservation; restored metadata must match its durable attempt. */
    suspend fun adoptScheduled(
        owner: ActionId,
        parent: SessionRef,
        existing: HelperId? = null,
        expectedRequest: RequestId? = null,
    ): HelperLease?

    /**
     * Barrier after an uncertain prompt call: revokes [request] only before native submission and never stops
     * submitted or accepted work. Unconfirmed means the exact result must be observed instead.
     */
    suspend fun revokeUnsubmitted(helper: HelperId, request: RequestId): HelperCancellation
}
