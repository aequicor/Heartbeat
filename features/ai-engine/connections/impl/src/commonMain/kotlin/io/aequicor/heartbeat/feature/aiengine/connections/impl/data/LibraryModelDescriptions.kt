package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.DescribedModel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelDescriptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull

/** Reads the profile's model library only while adaptive subagents are on; a loading library shows nothing yet. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class LibraryModelDescriptions(
    private val toggles: FeatureToggles,
    private val library: Lazy<ModelProfilesView>,
) : ModelDescriptions {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<Set<DescribedModel>?> = toggles.observe(AdaptiveSubagentsEnabled)
        .distinctUntilChanged()
        .flatMapLatest { isEnabled -> if (isEnabled) described() else flowOf(null) }

    private fun described(): Flow<Set<DescribedModel>> = library.value.state
        .mapNotNull { state ->
            (state as? ModelProfilesState.Ready)?.profiles?.mapTo(mutableSetOf()) {
                DescribedModel(it.key.engine, it.key.model)
            }
        }
        .distinctUntilChanged()
}
