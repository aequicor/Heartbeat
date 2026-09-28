package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Moves a macOS host window from otherwise unhandled presses inside this area.
 * The desktop entry point must install its window scope with `HbWindowDragProvider`.
 * Child buttons and editors retain their gestures; mobile and hosts without a provider
 * use the same layout without window interaction. The wrapper adds no styling or semantics.
 */
@Composable
@NonRestartableComposable
fun HbWindowDragArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    HbPlatformWindowDragArea(modifier, content)
}

@Composable
internal expect fun HbPlatformWindowDragArea(modifier: Modifier = Modifier, content: @Composable () -> Unit)

@Preview
@Composable
private fun WindowDragAreaLightPreview() {
    HbTheme(darkTheme = false) {
        HbWindowDragArea(Modifier.padding(HbTheme.spacing.l)) { HbText("Heartbeat") }
    }
}

@Preview
@Composable
private fun WindowDragAreaDarkPreview() {
    HbTheme(darkTheme = true) {
        HbWindowDragArea(Modifier.padding(HbTheme.spacing.l)) { HbText("Heartbeat") }
    }
}
