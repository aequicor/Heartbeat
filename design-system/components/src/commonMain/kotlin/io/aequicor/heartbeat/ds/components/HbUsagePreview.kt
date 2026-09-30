package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

@Preview
@Composable
private fun UsageLightPreview() {
    HbTheme(darkTheme = false) { UsagePreviewContent() }
}

@Preview
@Composable
private fun UsageDarkPreview() {
    HbTheme(darkTheme = true) { UsagePreviewContent() }
}

@Composable
private fun UsagePreviewContent() {
    HbColumn {
        HbRow {
            for (percent in listOf(0, 61, 100)) {
                HbComposerUsageButton(percent, "Limits", false, {}) { }
            }
            HbComposerUsageButton(null, "Limits", false, {}) { }
            HbComposerUsageButton(61, "Disabled", false, {}, enabled = false) { }
        }
        HbProgressBar(0f)
        HbProgressBar(0.61f)
        HbProgressBar(1f, color = HbTheme.colors.warning)
    }
}
