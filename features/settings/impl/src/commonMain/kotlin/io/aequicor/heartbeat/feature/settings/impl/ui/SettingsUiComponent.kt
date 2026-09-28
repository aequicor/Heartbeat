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

/**
 * Placeholder of the section stack before a section is chosen. It must be a [ComposableComponent]: the wide window
 * renders the stack at once, and the navigation host rejects components that cannot draw themselves.
 */
internal data object SettingsBlankUi : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) = Unit
}
