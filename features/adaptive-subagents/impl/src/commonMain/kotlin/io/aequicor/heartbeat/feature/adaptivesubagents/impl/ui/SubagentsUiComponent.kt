package io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class SubagentsUiComponent(private val component: SubagentsComponent, private val isEmbedded: Boolean) :
    ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        SubagentsScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
