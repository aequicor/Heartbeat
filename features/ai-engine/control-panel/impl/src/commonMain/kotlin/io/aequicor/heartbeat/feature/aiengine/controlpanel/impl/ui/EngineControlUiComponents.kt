package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlListComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlPanelComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_list_engines
import org.jetbrains.compose.resources.stringResource

/** Rendering adapter of the engine list; inside the settings window only the content is drawn. */
internal class EngineControlListUiComponent(private val component: EngineControlListComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val isStandalone = !component.isEmbedded
        EngineControlListScreen(
            component.model,
            stringResource(Res.string.control_list_engines).takeIf { isStandalone },
            component::open,
            component::close.takeIf { isStandalone },
            modifier,
        )
    }
}

/** Rendering adapter of an engine panel. */
internal class EngineControlPanelUiComponent(private val component: EngineControlPanelComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        EngineControlPanelScreen(component.model, component::close, modifier)
    }
}
