package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme

@Preview(name = "Functional panel · light", widthDp = 360, heightDp = 160)
@Composable
private fun PanelLightPreview() {
    HbTheme(darkTheme = false) { PanelPreviewContent() }
}

@Preview(name = "Functional panel · dark", widthDp = 360, heightDp = 160)
@Composable
private fun PanelDarkPreview() {
    HbTheme(darkTheme = true) { PanelPreviewContent() }
}

@Composable
private fun PanelPreviewContent() {
    HbPanel(modifier = Modifier.fillMaxWidth().padding(HbTheme.spacing.m)) {
        HbColumn(modifier = Modifier.fillMaxWidth(), gap = HbTheme.elevation.none) {
            HbText("Studio", Modifier.padding(HbTheme.spacing.m), style = HbTheme.typography.label)
            HbDivider()
            HbText("Space for your next idea.", Modifier.padding(HbTheme.spacing.l))
        }
    }
}
