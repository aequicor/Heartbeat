package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import kotlinx.coroutines.flow.Flow

/** Entry points gated by feature toggles. */
@Inject
@ContributesBinding(AiStudioScope::class)
class ToggleStudioEntries(toggles: FeatureToggles) : StudioEntries {
    override val showsConnections: Flow<Boolean> = toggles.observe(EngineConnectionsEnabled)
}
