package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.ContextSettingsComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class ContextSettingsUiComponent(
    private val component: ContextSettingsComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        ContextSettingsScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
