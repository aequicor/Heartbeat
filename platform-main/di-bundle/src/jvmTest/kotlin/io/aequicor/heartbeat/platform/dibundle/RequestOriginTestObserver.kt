package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.scheduler.api.spi.RequestOriginAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver

/** Uses the actual profile contribution set and actual native target supplied by Studio. */
@ContributesIntoSet(ProfileScope::class)
@ExposeImplBinding
@SingleIn(ProfileScope::class)
@Inject
class RequestOriginTestObserver : ScheduledRequestOriginObserver {
    var beforeRecord: suspend (RequestOriginAttempt) -> Unit = {}
    override suspend fun record(attempt: RequestOriginAttempt) = beforeRecord(attempt)
}

@ContributesTo(ProfileScope::class)
interface RequestOriginTestAccessors {
    val requestOriginObserver: RequestOriginTestObserver
}
