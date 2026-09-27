package io.aequicor.heartbeat.feature.welcome.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.welcome.impl.presentation.component.WelcomeComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class WelcomeUiComponent(private val component: WelcomeComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) = WelcomeScreen(component.model, modifier)
}
