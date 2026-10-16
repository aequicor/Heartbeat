package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserPageZoom
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserZoomPolicy
import kotlinx.coroutines.flow.Flow

@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleBrowserZoomPolicy(private val toggles: FeatureToggles) : BrowserZoomPolicy {
    override fun observe(): Flow<Boolean> = toggles.observe(BrowserPageZoom)
}
