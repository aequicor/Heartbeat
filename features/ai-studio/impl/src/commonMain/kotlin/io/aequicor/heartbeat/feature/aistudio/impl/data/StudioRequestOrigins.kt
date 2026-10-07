package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.spi.RequestOriginAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Does not authorize submission. Every durable observer completes before hooks and the existing native gate. */
@Inject
internal class StudioRequestOrigins(private val observers: Lazy<Set<ScheduledRequestOriginObserver>>) {
    suspend fun record(session: SessionRef, request: RequestId, causes: Set<RequestInitiator>) {
        if (causes.isEmpty()) return
        val attempt = RequestOriginAttempt(session, request, causes)
        for (observer in observers.value) {
            observer.record(attempt)
            currentCoroutineContext().ensureActive()
        }
        currentCoroutineContext().ensureActive()
    }
}
