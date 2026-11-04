package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioEffortOverride
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioLastStartedRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPreferences
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Last accepted ordinary chat wins over unsent preferences; missing exact routes are never substituted. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeStudioDefaults(
    preferences: StudioPreferences,
    selections: ModelSelections,
    efforts: EffortChoicesView,
    lastStarted: StudioLastStartedRun,
): Flow<RunSettings> = flow {
    efforts.state.first { it is EffortConfigurationState.Ready }
    emitAll(
        selections.observe().flatMapLatest { selection ->
            combine(
                preferences.observe(
                    DefaultRunSettings.copy(modelId = selection.defaultTarget?.studioModelId().orEmpty()),
                ),
                lastStarted.observe(),
                efforts.state,
            ) { choices, started, effortState ->
                started?.let {
                    choices.copy(
                        modelId = it.modelId,
                        approval = it.approval,
                        nativeEffort = StudioEffortOverride(it.modelId, it.reasoningEffort),
                    )
                } ?: choices.copy(
                    nativeEffort = studioModelTarget(choices.modelId)?.let { target ->
                        StudioEffortOverride(
                            choices.modelId,
                            (effortState as? EffortConfigurationState.Ready)?.effortFor(target),
                        )
                    },
                )
            }
        },
    )
}
