package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

@Preview
@Composable
private fun PopoverLightPreview() {
    HbTheme(darkTheme = false) { PopoverPreviewContent() }
}

@Preview
@Composable
private fun PopoverDarkPreview() {
    HbTheme(darkTheme = true) { PopoverPreviewContent() }
}

@Composable
private fun PopoverPreviewContent() {
    Box {
        HbIconButton(HbIcons.ZoomIn, "Масштаб", {}, isSelected = true)
        HbPopover(true, {}, "Масштаб") {
            HbRow {
                HbText("125%")
                HbIconButton(HbIcons.Minus, "Уменьшить", {})
                HbIconButton(HbIcons.Plus, "Увеличить", {}, enabled = false)
                HbButton("Сбросить", {})
            }
        }
    }
}
