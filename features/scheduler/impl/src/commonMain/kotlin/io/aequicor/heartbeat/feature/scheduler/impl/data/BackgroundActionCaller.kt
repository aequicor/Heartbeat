package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator

/** Trusted parent and optional exact request; a legacy parent alone never invents a causal identity. */
internal data class BackgroundActionCaller(val session: SessionRef, val request: RequestId? = null) {
    fun initiator(): RequestInitiator? = request?.let { RequestInitiator(session, it) }
    override fun toString(): String = "BackgroundActionCaller(***)"
}
