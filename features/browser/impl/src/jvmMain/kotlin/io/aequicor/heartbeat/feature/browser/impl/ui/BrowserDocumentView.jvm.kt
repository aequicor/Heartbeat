package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import io.aequicor.heartbeat.ds.components.LocalHbNativeContentViewport
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanels
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_document_page
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
        val panel = remember(surface) { RememberedPanel(panels.create(surface.document)) }.panel
        val isUnavailable by panel.unavailable.collectAsState()
        if (isUnavailable) {
            BrowserDocumentNote(stringResource(Res.string.browser_engine_unavailable), modifier)
        } else {
            // The panel's own background shows until the page paints: the surface color, not Swing's white.
            val name = surface.title.ifBlank { stringResource(Res.string.browser_document_page) }
            val viewport = LocalHbNativeContentViewport.current
            val density = LocalDensity.current.density
            var bounds by remember { mutableStateOf<DocumentViewBounds?>(null) }
            val clip = bounds?.let { measured ->
                val visible = if (viewport == null) measured.visible else measured.visible.intersect(viewport)
                documentViewportBounds(measured.full, visible, density)
            } ?: IntRect.Zero
            SwingPanel(
                background = HbTheme.colors.surface,
                factory = { DocumentViewportPanel(panel.createComponent()) },
                update = { it.clipTo(clip) },
                modifier = modifier.semantics { contentDescription = name }.onGloballyPositioned {
                    bounds = DocumentViewBounds(it.boundsInWindow(clipBounds = false), it.boundsInWindow())
                },
            )
        }
    }
}

private data class DocumentViewBounds(val full: Rect, val visible: Rect)

/** Releases its panel when the composition forgets it, or abandons it before it was ever shown. */
private class RememberedPanel(val panel: DesktopDocumentPanel) : RememberObserver {
    override fun onRemembered() = Unit

    override fun onForgotten() = panel.release()

    override fun onAbandoned() = panel.release()
}
