package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class ComputerUseUiComponent(private val component: ComputerUseComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        ComputerUseScreen(component.model, component::close, modifier)
    }
}
