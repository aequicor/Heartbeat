package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserPhase
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState

@Preview
@Composable
private fun BrowserLightPreview() {
    HbTheme(darkTheme = false) {
        BrowserScreenContent(BrowserScreenState(phase = BrowserPhase.Ready), {})
    }
}

@Preview
@Composable
private fun BrowserDarkPreview() {
    HbTheme(darkTheme = true) {
        BrowserScreenContent(BrowserScreenState(phase = BrowserPhase.Ready), {})
    }
}
