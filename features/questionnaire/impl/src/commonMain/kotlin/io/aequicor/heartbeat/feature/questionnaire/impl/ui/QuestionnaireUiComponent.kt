package io.aequicor.heartbeat.feature.questionnaire.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.component.QuestionnaireComponent
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenState
import pro.respawn.flowmvi.dsl.collect

/** Route rendering adapter keeping Compose screens out of the presentation component. */
internal class QuestionnaireUiComponent(private val component: QuestionnaireComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val model = component.model
        val state by produceState(QuestionnaireScreenState(), model) {
            model.store.collect { states.collect { value = it } }
        }
        QuestionnaireScreen(state, model.store::intent, modifier)
    }
}
