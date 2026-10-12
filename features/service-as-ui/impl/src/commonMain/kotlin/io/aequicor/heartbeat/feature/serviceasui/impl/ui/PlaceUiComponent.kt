package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.PlaceComponent

/** Route rendering adapter keeping Compose screens out of the presentation component. */
internal class PlaceUiComponent(private val component: PlaceComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        PlaceScreen(component.model, modifier)
    }
}
