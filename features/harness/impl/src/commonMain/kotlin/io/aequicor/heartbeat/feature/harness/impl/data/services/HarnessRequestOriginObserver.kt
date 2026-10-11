package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import io.aequicor.heartbeat.feature.scheduler.api.spi.RequestOriginAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Causal restrictions survive feature-off and restarts; this contribution never starts a runtime or grants access. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessRequestOriginObserver(private val storage: Lazy<HarnessRequestAncestry>) :
    ScheduledRequestOriginObserver {
    override suspend fun record(attempt: RequestOriginAttempt) {
        var combined = storage.value.lookup(attempt.session, attempt.request) ?: HarnessCallOrigin()
        for (cause in attempt.causes) {
            val origin = storage.value.lookup(cause.session, cause.request) ?: HarnessCallOrigin()
            combined = combined.merge(origin)
        }
        if (combined.isHookRestricted || combined.sendChain.isNotEmpty()) {
            storage.value.restrict(attempt.session, attempt.request, combined)
        }
        currentCoroutineContext().ensureActive()
    }
}
