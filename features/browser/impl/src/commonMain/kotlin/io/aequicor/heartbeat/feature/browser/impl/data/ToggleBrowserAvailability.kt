package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserEnabled
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAvailability
import kotlinx.coroutines.flow.Flow

@ContributesBinding(BrowserScope::class)
@Inject
internal class ToggleBrowserAvailability(private val toggles: FeatureToggles) : BrowserAvailability {
    override fun observe(): Flow<Boolean> = toggles.observe(BrowserEnabled)
}
