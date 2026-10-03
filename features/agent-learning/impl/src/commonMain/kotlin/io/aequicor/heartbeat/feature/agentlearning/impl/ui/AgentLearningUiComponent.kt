package io.aequicor.heartbeat.feature.agentlearning.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.agentlearning.impl.presentation.AgentLearningComponent

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AgentLearningUiComponent(
    private val component: AgentLearningComponent,
    private val isEmbedded: Boolean,
) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        AgentLearningScreen(component.model, component::close.takeUnless { isEmbedded }, modifier)
    }
}
