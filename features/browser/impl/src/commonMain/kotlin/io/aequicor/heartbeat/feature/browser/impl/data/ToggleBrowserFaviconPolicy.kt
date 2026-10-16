package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserFavicons
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserFaviconPolicy

@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleBrowserFaviconPolicy(private val toggles: FeatureToggles) : BrowserFaviconPolicy {
    override suspend fun isEnabled() = toggles.get(BrowserFavicons)

    override fun observe() = toggles.observe(BrowserFavicons)
}
