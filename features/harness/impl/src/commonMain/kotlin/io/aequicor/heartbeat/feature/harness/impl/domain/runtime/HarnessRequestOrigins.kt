package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Trusted send owners register ancestry before submission. Entries survive Closed and terminal observations:
 * delayed notifications must retain the same restriction. This profile-local seam grants no helper affiliation
 * and never derives authority from payload, titles, tool arguments, session ids or request naming conventions.
 */
internal class HarnessRequestOrigins {
    private val requests = MutableStateFlow<Map<Pair<SessionRef, RequestId>, HarnessCallOrigin>>(emptyMap())
    private val log = Log.tag("HarnessRequestOrigins")

    @HighFrequency
    fun register(session: SessionRef, request: RequestId, origin: HarnessCallOrigin) {
        log.v { "register trusted request ancestry" }
        val key = session to request
        requests.update { previous ->
            previous + (key to (previous[key]?.merge(origin) ?: origin))
        }
    }

    fun origin(context: SessionHookContext): HarnessCallOrigin =
        context.request?.let { requests.value[context.session to it] } ?: HarnessCallOrigin()
}
