package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_preparation_download
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_preparation_install
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_preparation_invalid
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_preparation_network
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_preparation_storage
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_preparation_unavailable
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationPhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnginePreparationUi
import org.jetbrains.compose.resources.stringResource

/** Lives above the workspace without covering its stop/retry controls or exposing native diagnostics. */
@Composable
internal fun StudioEnginePreparation(state: EnginePreparationUi, modifier: Modifier = Modifier) {
    val resource = when (state.phase) {
        EnginePreparationPhaseUi.Downloading -> Res.string.engine_preparation_download
        EnginePreparationPhaseUi.Installing -> Res.string.engine_preparation_install
        EnginePreparationPhaseUi.Network -> Res.string.engine_preparation_network
        EnginePreparationPhaseUi.Storage -> Res.string.engine_preparation_storage
        EnginePreparationPhaseUi.InvalidRuntime -> Res.string.engine_preparation_invalid
        EnginePreparationPhaseUi.Unavailable -> Res.string.engine_preparation_unavailable
    }
    HbColumn(
        modifier.fillMaxWidth().background(HbTheme.surfaces.assistant)
            .padding(HbTheme.spacing.m).testTag("studio-engine-preparation"),
        gap = HbTheme.spacing.s,
    ) {
        val message = stringResource(resource, state.engine)
        if (state.phase == EnginePreparationPhaseUi.Downloading || state.phase == EnginePreparationPhaseUi.Installing) {
            HbLoadingState(message)
            state.percent?.let { HbProgressBar(it / 100f, Modifier.fillMaxWidth()) }
        } else {
            HbText(message, color = HbTheme.colors.textSecondary)
        }
    }
}
