package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities

/** Theme, measurements and current modal attachment of one retained native document page. */
internal class DesktopDocumentPresentation {
    val theme = MutableStateFlow<DesktopDocumentTheme?>(null)
    val measurements = DesktopDocumentMeasurements { action -> SwingUtilities.invokeLater(action) }
    val dismissal = DesktopDocumentDismissal()
}
