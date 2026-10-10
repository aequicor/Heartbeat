package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin

/** Increment only this harness's chain; a relay cannot erase another harness's ancestry. */
internal fun HarnessCallOrigin.forSend(harness: HarnessId): HarnessCallOrigin {
    check(!isHookRestricted) { "Session send is unavailable from hooks" }
    val depth = (sendChain[harness] ?: 0) + 1
    check(depth <= HarnessLimits.SEND_CHAIN) { "Harness send chain quota reached" }
    return HarnessCallOrigin(isHookRestricted, sendChain + (harness to depth))
}
