package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component.ConnectionsPresentation
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component.EngineConnectionsComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class EngineConnectionsUiComponent(private val component: EngineConnectionsComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val presentation by component.presentation.collectAsState()
        if (presentation == ConnectionsPresentation.Pending) return
        EngineConnectionsScreen(
            component.model,
            component::openWizard,
            if (presentation == ConnectionsPresentation.Embedded) null else component::close,
            modifier,
        )
    }
}
