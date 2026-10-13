package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanels
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_engine_unavailable
import org.jetbrains.compose.resources.stringResource

/** The document's own browser while shown; it closes when the view leaves the screen or the document closes. */
@Composable
internal actual fun BrowserDocumentView(surface: BrowserDocumentSurface, modifier: Modifier) {
    val panels = surface.natives as? DesktopDocumentPanels
    if (panels == null) {
        BrowserDocumentNote(stringResource(Res.string.browser_engine_unavailable), modifier)
    } else {
        DocumentPanel(panels, surface, modifier)
    }
}

@Composable
private fun DocumentPanel(
    panels: DesktopDocumentPanels,
    surface: BrowserDocumentSurface,
    modifier: Modifier = Modifier,
) {
    key(surface) {
        val panel = remember(surface) { panels.create(surface.document) }
        DisposableEffect(panel) { onDispose(panel::release) }
        val isUnavailable by panel.unavailable.collectAsState()
        if (isUnavailable) {
            BrowserDocumentNote(stringResource(Res.string.browser_engine_unavailable), modifier)
        } else {
            SwingPanel(
                factory = panel::createComponent,
                modifier = modifier.semantics { contentDescription = surface.title },
            )
        }
    }
}
