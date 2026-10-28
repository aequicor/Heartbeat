package io.aequicor.heartbeat.feature.githubconnector.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectorModel
import pro.respawn.flowmvi.dsl.collect

/** Compose adapter; navigation and the model remain outside the UI package. */
internal class GitHubConnectorUiComponent(private val model: GitHubConnectorModel, private val onBack: (() -> Unit)?) :
    ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val state by produceState(model.current.value, model) {
            model.store.collect { states.collect { value = it } }
        }
        val onIntent = remember(model) { model.store::intent }
        GitHubConnectorContent(state, onIntent, onBack, modifier)
    }
}
