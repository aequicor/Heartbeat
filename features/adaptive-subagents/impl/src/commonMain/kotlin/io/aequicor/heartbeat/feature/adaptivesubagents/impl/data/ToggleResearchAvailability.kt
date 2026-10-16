package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsResearch
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.ResearchAvailability
import kotlinx.coroutines.flow.Flow

/** Research availability from its toggle. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ToggleResearchAvailability(private val toggles: FeatureToggles) : ResearchAvailability {
    override fun observe(): Flow<Boolean> = toggles.observe(AdaptiveSubagentsResearch)
}
