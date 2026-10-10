package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryComponent

/** Library rendering adapter; an embedded settings section has no back button. */
internal class HarnessLibraryUiComponent(
    private val component: HarnessLibraryComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val onBack = remember(component, isEmbedded) { component::close.takeUnless { isEmbedded } }
        HarnessLibraryScreen(component.model, component::openDetail, onBack, modifier)
    }
}
