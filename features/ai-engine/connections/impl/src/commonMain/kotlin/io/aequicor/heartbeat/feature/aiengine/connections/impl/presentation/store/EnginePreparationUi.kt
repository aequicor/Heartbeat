package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparationFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase

/** Passive first-use setup of an engine, available even while the management feature is off. */
@Immutable
data class EnginePreparationUi(val phase: PreparationPhaseUi, val percent: Int? = null)

/** Localized preparation and safe error categories shown by the connection screen. */
enum class PreparationPhaseUi { Downloading, Installing, Network, Storage, InvalidRuntime, Unavailable }

internal fun EnginePreparation.toUi(): EnginePreparationUi? = when (this) {
    EnginePreparation.Idle, EnginePreparation.Ready -> null

    EnginePreparation.Installing -> EnginePreparationUi(PreparationPhaseUi.Installing)

    is EnginePreparation.Downloading -> EnginePreparationUi(
        PreparationPhaseUi.Downloading,
        total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it * PERCENT_SCALE).toInt().coerceIn(0, PERCENT_SCALE) },
    )

    is EnginePreparation.Failed -> EnginePreparationUi(
        when (reason) {
            EnginePreparationFailure.Network -> PreparationPhaseUi.Network
            EnginePreparationFailure.Storage -> PreparationPhaseUi.Storage
            EnginePreparationFailure.InvalidRuntime -> PreparationPhaseUi.InvalidRuntime
            EnginePreparationFailure.Unavailable -> PreparationPhaseUi.Unavailable
        },
    )
}

private const val PERCENT_SCALE = 100

internal fun EngineJob?.installationProgress(): EnginePreparationUi? {
    if (this == null || phase.isFinished) return null
    if (action != EngineAction.Install && action != EngineAction.Update) return null
    return when (val current = phase) {
        is JobPhase.Downloading -> EnginePreparationUi(
            PreparationPhaseUi.Downloading,
            current.total?.takeIf { it > 0 }
                ?.let { (current.bytes.toDouble() / it * PERCENT_SCALE).toInt().coerceIn(0, PERCENT_SCALE) },
        )

        else -> EnginePreparationUi(PreparationPhaseUi.Installing)
    }
}
