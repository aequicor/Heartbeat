package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.EngineConnectionsScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.UnifiedSettingsPolicy
import io.aequicor.heartbeat.feature.settings.api.UnifiedSettings

/** Reads the unified settings toggle through the app-owned toggle service. */
@Inject
@ContributesBinding(EngineConnectionsScope::class)
internal class ToggleUnifiedSettingsPolicy(private val toggles: FeatureToggles) : UnifiedSettingsPolicy {
    override suspend fun isUnified(): Boolean = toggles.get(UnifiedSettings)
}
