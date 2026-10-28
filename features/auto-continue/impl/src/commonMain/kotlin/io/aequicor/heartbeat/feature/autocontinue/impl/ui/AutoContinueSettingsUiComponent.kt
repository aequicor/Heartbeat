package io.aequicor.heartbeat.feature.autocontinue.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AutoContinueSettingsUiComponent(
    private val component: AutoContinueSettingsComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        AutoContinueSettingsScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
