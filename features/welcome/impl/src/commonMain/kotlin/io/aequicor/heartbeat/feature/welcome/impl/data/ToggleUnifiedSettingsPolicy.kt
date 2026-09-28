package io.aequicor.heartbeat.feature.welcome.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings
import io.aequicor.heartbeat.feature.welcome.impl.di.scope.WelcomeScope
import io.aequicor.heartbeat.feature.welcome.impl.domain.UnifiedSettingsPolicy

/** Reads the unified settings toggle through the app-owned toggle service. */
@Inject
@ContributesBinding(WelcomeScope::class)
internal class ToggleUnifiedSettingsPolicy(private val toggles: FeatureToggles) : UnifiedSettingsPolicy {
    override suspend fun isUnified(): Boolean = toggles.get(UnifiedSettings)
}
