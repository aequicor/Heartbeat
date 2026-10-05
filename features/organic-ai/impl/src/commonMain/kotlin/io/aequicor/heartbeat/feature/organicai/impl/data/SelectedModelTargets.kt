package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.organicai.impl.domain.DefaultTargets
import kotlinx.coroutines.flow.first

/** The default model the user selected in "Models and engines". */
@ContributesBinding(ProfileScope::class)
@Inject
internal class SelectedModelTargets(private val selections: ModelSelections) : DefaultTargets {
    override suspend fun default(): EngineTarget? = selections.observe().first().defaultTarget
}
