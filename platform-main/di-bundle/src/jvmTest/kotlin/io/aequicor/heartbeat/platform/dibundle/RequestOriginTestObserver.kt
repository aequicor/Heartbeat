package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.scheduler.api.spi.RequestOriginAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver

/** Uses the actual native target supplied by Studio; [TestEngineBundle] brings one per profile to the scheduler. */
class RequestOriginTestObserver : ScheduledRequestOriginObserver {
    var beforeRecord: suspend (RequestOriginAttempt) -> Unit = {}
    override suspend fun record(attempt: RequestOriginAttempt) = beforeRecord(attempt)
}

@ContributesTo(ProfileScope::class)
interface RequestOriginTestAccessors {
    val requestOriginObserver: RequestOriginTestObserver
}
