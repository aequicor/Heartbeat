package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.browser.impl.presentation.component.BrowserComponent

/** Rendering adapter keeps Compose screens out of presentation components. */
internal class BrowserUiComponent(private val component: BrowserComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) = BrowserScreen(component.model, modifier)
}
