package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component.TogglesPanelComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class TogglesPanelUiComponent(private val component: TogglesPanelComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) = TogglesPanelScreen(component.model, component::close, modifier)
}
