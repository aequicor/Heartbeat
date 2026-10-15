package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparationFailure
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationPhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationUi
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Technical runtime setup is projected independently of managed-engine settings and of chat turn ownership. */
@HighFrequency
internal suspend fun observeEnginePreparation(pipeline: StudioPipeline, facade: EngineFacade) {
    facade.engines.state.map { engines ->
        engines.mapNotNull { info -> info.preparation.toUi(info.descriptor.title) }.toImmutableList()
    }.distinctUntilChanged().collect { setup ->
        Log.tag("StudioEnginePreparation").v { "Engine preparation changed" }
        pipeline.updateState { copy(enginePreparations = setup) }
    }
}

private fun EnginePreparation.toUi(title: String): EnginePreparationUi? = when (this) {
    EnginePreparation.Idle, EnginePreparation.Ready -> null

    EnginePreparation.Installing -> EnginePreparationUi(title, EnginePreparationPhaseUi.Installing)

    is EnginePreparation.Downloading -> EnginePreparationUi(
        title,
        EnginePreparationPhaseUi.Downloading,
        total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it * PERCENT_SCALE).toInt().coerceIn(0, PERCENT_SCALE) },
    )

    is EnginePreparation.Failed -> EnginePreparationUi(
        title,
        when (reason) {
            EnginePreparationFailure.Network -> EnginePreparationPhaseUi.Network
            EnginePreparationFailure.Storage -> EnginePreparationPhaseUi.Storage
            EnginePreparationFailure.InvalidRuntime -> EnginePreparationPhaseUi.InvalidRuntime
            EnginePreparationFailure.Unavailable -> EnginePreparationPhaseUi.Unavailable
        },
    )
}

private const val PERCENT_SCALE = 100
