package io.aequicor.heartbeat.chromiumplugin.panel.diagram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.ds.components.HbDiagramsProvider
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Connection and feature changes discard cached previews; old apps and disabled renderers leave fences as code. */
@Composable
internal fun PanelDiagrams(
    link: PanelLink,
    host: PanelHost,
    dispatchers: DispatcherProvider,
    content: @Composable () -> Unit,
) {
    val status by link.status.collectAsState()
    val background = HbTheme.colors.surfaceElevated.toArgb()
    val renderer = remember(link, host, dispatchers, background) {
        PanelDiagramRenderer(link, host, dispatchers, background)
    }
    val isAvailable = status.status == "ready" && status.features?.isDiagrams == true
    HbDiagramsProvider(if (isAvailable) renderer else null, diagramLabels(), content)
}
