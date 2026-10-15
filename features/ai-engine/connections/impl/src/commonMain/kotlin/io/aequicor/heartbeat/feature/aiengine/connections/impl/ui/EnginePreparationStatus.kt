package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePreparationUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.PreparationPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_preparation_download
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_preparation_install
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_preparation_invalid
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_preparation_network
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_preparation_storage
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_preparation_unavailable
import org.jetbrains.compose.resources.stringResource

/** Preparation is part of the current operation: its existing cancel/retry controls remain authoritative. */
@Composable
internal fun EnginePreparationStatus(state: EnginePreparationUi, modifier: Modifier = Modifier) {
    val resource = when (state.phase) {
        PreparationPhaseUi.Downloading -> Res.string.engine_preparation_download
        PreparationPhaseUi.Installing -> Res.string.engine_preparation_install
        PreparationPhaseUi.Network -> Res.string.engine_preparation_network
        PreparationPhaseUi.Storage -> Res.string.engine_preparation_storage
        PreparationPhaseUi.InvalidRuntime -> Res.string.engine_preparation_invalid
        PreparationPhaseUi.Unavailable -> Res.string.engine_preparation_unavailable
    }
    HbColumn(modifier.fillMaxWidth().testTag("engine-preparation"), gap = HbTheme.spacing.s) {
        if (state.phase == PreparationPhaseUi.Downloading || state.phase == PreparationPhaseUi.Installing) {
            HbLoadingState(stringResource(resource))
            state.percent?.let { HbProgressBar(it / 100f, Modifier.fillMaxWidth()) }
        } else {
            HbText(stringResource(resource), color = HbTheme.colors.textSecondary)
        }
    }
}
