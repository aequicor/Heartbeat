package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class ProjectConfigUiComponent(
    private val component: ProjectConfigComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        ProjectConfigScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
