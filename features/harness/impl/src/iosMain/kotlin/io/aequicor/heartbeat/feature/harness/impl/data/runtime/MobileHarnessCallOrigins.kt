package io.aequicor.heartbeat.feature.harness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import kotlin.coroutines.CoroutineContext

/** No script execution on this platform; preserve explicit coroutine markers without a thread-local carrier. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class MobileHarnessCallOrigins : HarnessCallOrigins {
    @HighFrequency
    override fun current(): HarnessCallOrigin = HarnessCallOrigin()

    @HighFrequency
    override fun context(origin: HarnessCallOrigin): CoroutineContext = HarnessOriginContext(origin)
}
