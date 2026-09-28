package io.aequicor.heartbeat.feature.settings.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.settings.impl.presentation.component.SettingsComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class SettingsUiComponent(private val component: SettingsComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) = SettingsScreen(component, modifier)
}
