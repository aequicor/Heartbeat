package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test-only persistence seam; production uses exact Room point reads. */
internal class MemoryHarnessRequestAncestry : HarnessRequestAncestry {
    private val mutex = Mutex()
    private val entries = mutableMapOf<Pair<SessionRef, RequestId>, HarnessCallOrigin>()
    var failure: Exception? = null
    var beforeRestrict: suspend () -> Unit = {}
    var beforeLookup: suspend () -> Unit = {}
    override suspend fun restrict(session: SessionRef, request: RequestId, origin: HarnessCallOrigin) {
        beforeRestrict()
        failure?.let { throw it }
        mutex.withLock {
            val key = session to request
            entries[key] = entries[key]?.merge(origin) ?: origin
        }
    }
    override suspend fun lookup(session: SessionRef, request: RequestId): HarnessCallOrigin? {
        beforeLookup()
        failure?.let { throw it }
        return mutex.withLock { entries[session to request] }
    }
}
