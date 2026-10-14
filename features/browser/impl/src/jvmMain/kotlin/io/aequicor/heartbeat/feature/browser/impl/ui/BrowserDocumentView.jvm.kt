package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.LocalHbDialogDismissRequest
import io.aequicor.heartbeat.ds.components.LocalHbDialogOverlayInsets
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentAttachment
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanels
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_document_page
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_engine_unavailable
import org.jetbrains.compose.resources.stringResource

/** One attachment of the document's retained page; unmounting detaches it without closing or reloading its DOM. */
@Composable
internal actual fun BrowserDocumentView(surface: BrowserDocumentSurface, modifier: Modifier) {
    val panels = surface.natives as? DesktopDocumentPanels
    if (panels == null) {
        BrowserDocumentFallback(surface, stringResource(Res.string.browser_engine_unavailable), modifier)
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
        val panel = remember(surface) { panels.retain(surface.document) }
        if (panel == null) {
            BrowserDocumentNote(stringResource(Res.string.browser_engine_unavailable), modifier)
        } else {
            RetainedDocumentPanel(panel, surface, modifier)
        }
    }
}

@Composable
private fun RetainedDocumentPanel(
    panel: DesktopDocumentPanel,
    surface: BrowserDocumentSurface,
    modifier: Modifier = Modifier,
) {
    val attachment = remember(panel) { RememberedAttachment(panel.newAttachment()) }.attachment
    val isUnavailable by panel.unavailable.collectAsState()
    val size by panel.contentSize.collectAsState()
    var viewportWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val dimensions = HbTheme.dimensions
    val overlay = LocalHbDialogOverlayInsets.current
    val theme = HbTheme.colors.browserDocumentTheme(
        HbTheme.typography,
        HbTheme.spacing,
        dimensions,
        density.fontScale,
        overlay,
    )
    val dismiss = LocalHbDialogDismissRequest.current
    val cornerRadius = if (overlay.width.value > 0 && overlay.height.value > 0) {
        HbTheme.shapes.large.topStart.toPx(Size.Zero, density).toDouble() / density.density
    } else {
        0.0
    }
    val backdrop = if (dismiss != null) HbTheme.colors.scrim else HbTheme.surfaces.assistant
    val cornerColor = java.awt.Color(backdrop.toArgb(), true)
    SideEffect {
        panel.updateTheme(theme)
        attachment.updateDismissRequest(dismiss)
        attachment.updateCornerRadius(cornerRadius, cornerColor)
    }
    val intrinsicHeight = size?.logicalHeight(viewportWidth / density.density)?.dp ?: dimensions.webDocumentHeight
    val sized = modifier.documentSize(surface, intrinsicHeight, dimensions)
    if (isUnavailable) {
        BrowserDocumentNote(stringResource(Res.string.browser_engine_unavailable), sized)
    } else {
        // The panel's own background shows until the page paints: the surface color, not Swing's white.
        val name = surface.title.ifBlank { stringResource(Res.string.browser_document_page) }
        DocumentViewport(sized.onSizeChanged { viewportWidth = it.width }) {
            BrowserNativeView(
                background = HbTheme.colors.surface,
                factory = remember(attachment) { attachment::createComponent },
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
}

/** Detaches only its own lease; the registered document owns the retained native page. */
private class RememberedAttachment(val attachment: DesktopDocumentAttachment) : RememberObserver {
    override fun onRemembered() = Unit

    override fun onForgotten() = attachment.close()

    override fun onAbandoned() = attachment.close()
}
