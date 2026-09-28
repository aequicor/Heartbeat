package io.aequicor.heartbeat.feature.searchengine.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.searchengine.impl.domain.UnifiedSettingsPolicy
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings

/** Reads the unified settings toggle through the app-owned toggle service. */
@Inject
@ContributesBinding(AppScope::class)
internal class ToggleUnifiedSettingsPolicy(private val toggles: FeatureToggles) : UnifiedSettingsPolicy {
    override suspend fun isUnified(): Boolean = toggles.get(UnifiedSettings)
}
