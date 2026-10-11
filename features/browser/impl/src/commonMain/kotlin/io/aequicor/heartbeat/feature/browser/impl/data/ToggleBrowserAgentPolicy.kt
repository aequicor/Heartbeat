package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserAgentControl
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy

@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleBrowserAgentPolicy(private val toggles: FeatureToggles) : BrowserAgentPolicy {
    override fun observe() = toggles.observe(BrowserAgentControl)

    override suspend fun isEnabled() = toggles.get(BrowserAgentControl)
}
