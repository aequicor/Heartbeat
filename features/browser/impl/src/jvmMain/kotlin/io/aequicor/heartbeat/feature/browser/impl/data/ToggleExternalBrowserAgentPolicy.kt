package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.impl.domain.ExternalBrowserAgentPolicy
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** External browser control needs both the bridge and its agent control flags. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleExternalBrowserAgentPolicy(toggles: FeatureToggles) : ExternalBrowserAgentPolicy {
    override val availability: Flow<Boolean> = combine(
        toggles.observe(ChromiumBridgeEnabled),
        toggles.observe(ChromiumBridgeAgentControl),
    ) { isBridge, isAgent -> isBridge && isAgent }.distinctUntilChanged()
}
