package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessToolsComponent

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

/** Detail rendering adapter. */
internal class HarnessDetailUiComponent(private val component: HarnessDetailComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        HarnessDetailScreen(component.model, component.navigation, modifier)
    }
}

/** Item editor rendering adapter. */
internal class HarnessItemUiComponent(private val component: HarnessItemComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        HarnessItemScreen(component.model, component::close, modifier)
    }
}

/** Tool policy rendering adapter. */
internal class HarnessToolsUiComponent(private val component: HarnessToolsComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        HarnessToolsScreen(component.model, component::close, modifier)
    }
}
