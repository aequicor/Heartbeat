package io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ProfileEditorComponent

/** Rendering adapter assembled by the route entry. */
internal class ProfileEditorUiComponent(private val component: ProfileEditorComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        ProfileEditorScreen(component.model, component::close, modifier)
    }
}
