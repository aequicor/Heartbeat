package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/**
 * Trusted send owners persist ancestry before submission. Point reads survive profile/process restart without
 * an unbounded resident map. No authority is derived from payload, titles, arguments or request naming patterns.
 */
internal class HarnessRequestOrigins(private val ancestry: HarnessRequestAncestry) {
    private val log = Log.tag("HarnessRequestOrigins")

    @HighFrequency
    suspend fun register(session: SessionRef, request: RequestId, origin: HarnessCallOrigin) {
        log.v { "persist trusted request ancestry" }
        if (origin.isHookRestricted || origin.sendChain.isNotEmpty()) ancestry.restrict(session, request, origin)
    }

    suspend fun origin(context: SessionHookContext): HarnessCallOrigin = origin(context.session, context.request)

    /** Exact adapter/host request identity; a missing request never borrows the session's latest turn. */
    suspend fun origin(session: SessionRef, request: RequestId?): HarnessCallOrigin =
        request?.let { ancestry.lookup(session, it) } ?: HarnessCallOrigin()
}
