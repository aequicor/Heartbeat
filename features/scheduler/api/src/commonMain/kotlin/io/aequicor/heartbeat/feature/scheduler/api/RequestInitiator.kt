package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable

/**
 * Exact host-stamped request that initiated a durable operation. Carries execution restrictions only, never
 * ownership or permission. Legacy records have no initiator; no caller may reconstruct it from turn ids or text.
 */
@Serializable
public data class RequestInitiator(val session: SessionRef, val request: RequestId) {
    /** Same trusted causal reference when publishing an operation's scheduler output. */
    public fun origin(): EventOrigin.Session = EventOrigin.Session(session, request)
    override fun toString(): String = "RequestInitiator(***)"
}
