package io.aequicor.heartbeat.feature.checklist.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.checklist.impl.presentation.component.ChecklistComponent
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistScreenState
import pro.respawn.flowmvi.dsl.collect

/** Route rendering adapter keeping Compose screens out of the presentation component. */
internal class ChecklistUiComponent(private val component: ChecklistComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val model = component.model
        val state by produceState(ChecklistScreenState(), model) {
            model.store.collect { states.collect { value = it } }
        }
        ChecklistScreen(state, model.store::intent, modifier)
    }
}
