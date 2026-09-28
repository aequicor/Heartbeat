package io.aequicor.heartbeat.feature.searchengine.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.ProfileSettingsComponent
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.SearchSettingsPresentation

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class ProfileSettingsUiComponent(private val component: ProfileSettingsComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val presentation by component.presentation.collectAsState()
        if (presentation == SearchSettingsPresentation.Pending) return
        ProfileSettingsScreen(
            component.model,
            if (presentation == SearchSettingsPresentation.Embedded) null else component::close,
            modifier,
        )
    }
}
