package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities

/** Theme and intrinsic measurements of one view, never shared with a different view of the same document. */
internal class DesktopDocumentPresentation {
    val theme = MutableStateFlow<DesktopDocumentTheme?>(null)
    val measurements = DesktopDocumentMeasurements { action -> SwingUtilities.invokeLater(action) }
}
