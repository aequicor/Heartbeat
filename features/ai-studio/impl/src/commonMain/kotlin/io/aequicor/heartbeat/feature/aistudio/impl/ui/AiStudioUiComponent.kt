package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AiStudioUiComponent(private val component: AiStudioComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val isConnectionsShown by component.showsConnections.collectAsState(false)
        val isProfileSettingsShown by component.showsProfileSettings.collectAsState(false)
        val exits = remember(component, isConnectionsShown, isProfileSettingsShown) {
            StudioExits(
                onBack = component::close,
                onOpenToggles = component::openToggles,
                onOpenProfileSettings = if (isProfileSettingsShown) component::openProfileSettings else null,
                onOpenConnections = if (isConnectionsShown) component::openConnections else null,
            )
        }
        AiStudioScreen(component.model, exits, modifier)
    }
}
