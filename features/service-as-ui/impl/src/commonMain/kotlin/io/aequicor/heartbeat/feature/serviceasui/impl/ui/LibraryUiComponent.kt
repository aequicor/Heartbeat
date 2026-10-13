package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.LibraryComponent

/** Route rendering adapter; an [isEmbedded] library has no header of its own inside the settings window. */
internal class LibraryUiComponent(private val component: LibraryComponent, private val isEmbedded: Boolean) :
    ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        LibraryScreen(
            component.model,
            onOpen = component::openSurface,
            onBack = if (isEmbedded) null else component::close,
            modifier = modifier,
        )
    }
}
