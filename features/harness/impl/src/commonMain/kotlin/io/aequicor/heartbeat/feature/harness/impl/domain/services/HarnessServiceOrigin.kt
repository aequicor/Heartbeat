package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin

/**
 * Every script-scheduled agent turn (visible send or private wake) is forbidden from hooks and extends only this
 * harness's chain; a relay cannot erase another harness's ancestry, and a wake cannot restart a hook-driven loop.
 */
internal fun HarnessCallOrigin.forSend(harness: HarnessId): HarnessCallOrigin {
    check(!isHookRestricted) { "Agent turns cannot be scheduled from hooks" }
    val depth = (sendChain[harness] ?: 0) + 1
    check(depth <= HarnessLimits.SEND_CHAIN) { "Harness send chain quota reached" }
    return HarnessCallOrigin(isHookRestricted, sendChain + (harness to depth))
}
