package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPreferences
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Accepted start-page preferences with the current default route as fallback, after effort choices load. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeStudioDefaults(
    preferences: StudioPreferences,
    selections: ModelSelections,
    efforts: EffortChoicesView,
): Flow<RunSettings> = flow {
    efforts.state.first { it is EffortConfigurationState.Ready }
    emitAll(
        selections.observe().flatMapLatest { selection ->
            preferences.observe(
                DefaultRunSettings.copy(modelId = selection.defaultTarget?.studioModelId().orEmpty()),
            )
        },
    )
}
