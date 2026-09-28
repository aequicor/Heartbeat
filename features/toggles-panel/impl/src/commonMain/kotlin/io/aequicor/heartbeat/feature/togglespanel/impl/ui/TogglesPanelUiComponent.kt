package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component.PanelPresentation
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component.TogglesPanelComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class TogglesPanelUiComponent(private val component: TogglesPanelComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val presentation by component.presentation.collectAsState()
        when (presentation) {
            PanelPresentation.Pending -> Unit
            PanelPresentation.Embedded -> TogglesPanelScreen(component.model, onBack = null, modifier)
            PanelPresentation.Standalone -> TogglesPanelScreen(component.model, component::close, modifier)
        }
    }
}
