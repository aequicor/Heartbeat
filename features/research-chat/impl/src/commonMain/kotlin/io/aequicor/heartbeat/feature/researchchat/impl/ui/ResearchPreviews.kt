package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPhase
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState

@Preview
@Composable
private fun ResearchLightPreview() {
    HbTheme(darkTheme = false) {
        ResearchScreenContent(ResearchScreenState(phase = ResearchPhase.Ready), {}, {})
    }
}

@Preview
@Composable
private fun ResearchDarkPreview() {
    HbTheme(darkTheme = true) {
        ResearchScreenContent(ResearchScreenState(phase = ResearchPhase.Ready), {}, {})
    }
}
