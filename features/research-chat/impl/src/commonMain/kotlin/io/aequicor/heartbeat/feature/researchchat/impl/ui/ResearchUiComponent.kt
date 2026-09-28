package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.component.ResearchComponent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import pro.respawn.flowmvi.dsl.collect

/** Route rendering adapter keeping Compose screens out of the presentation component. */
internal class ResearchUiComponent(private val component: ResearchComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val model = component.model
        val state by produceState(ResearchScreenState(), model) {
            model.store.collect { states.collect { value = it } }
        }
        ResearchScreenContent(state, model.store::intent, component::close, modifier)
    }
}
