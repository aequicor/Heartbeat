package io.aequicor.heartbeat.feature.chromiumbridge.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ChromiumBridgeComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class ChromiumBridgeUiComponent(
    private val component: ChromiumBridgeComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        ChromiumBridgeScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
