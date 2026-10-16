package io.aequicor.heartbeat.feature.worktreemode.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.worktreemode.impl.presentation.BuildCapacityComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class BuildCapacityUiComponent(
    private val component: BuildCapacityComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        BuildCapacityScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
