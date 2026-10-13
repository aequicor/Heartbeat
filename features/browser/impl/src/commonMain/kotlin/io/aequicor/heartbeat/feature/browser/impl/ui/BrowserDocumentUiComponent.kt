package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.browser.impl.presentation.component.BrowserDocumentComponent

/** Rendering adapter of a document route. */
internal class BrowserDocumentUiComponent(private val component: BrowserDocumentComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val surface by component.surface.collectAsState()
        BrowserDocumentScreen(surface, modifier)
    }
}
