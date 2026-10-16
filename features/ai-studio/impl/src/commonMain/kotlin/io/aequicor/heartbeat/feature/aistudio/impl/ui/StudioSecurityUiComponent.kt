package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.StudioSecurityComponent

/** Rendering adapter assembled by the route entry. */
internal class StudioSecurityUiComponent(
    private val component: StudioSecurityComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        StudioSecurityScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
