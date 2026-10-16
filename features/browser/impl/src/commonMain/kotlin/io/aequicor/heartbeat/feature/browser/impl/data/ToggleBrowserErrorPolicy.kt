package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserErrorPages
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserErrorPolicy

@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleBrowserErrorPolicy(private val toggles: FeatureToggles) : BrowserErrorPolicy {
    override fun observe() = toggles.observe(BrowserErrorPages)
}
