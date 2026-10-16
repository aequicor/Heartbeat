package io.aequicor.heartbeat.feature.applogs.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsComponent
import pro.respawn.flowmvi.dsl.collect

/** Compose adapter; navigation and the model remain outside the UI package. */
internal class AppLogsUiComponent(private val component: AppLogsComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val state by produceState(component.model.current.value, component.model) {
            component.model.store.collect { states.collect { value = it } }
        }
        AppLogsContent(
            state,
            component.model.store::intent,
            onBack = if (component.isEmbedded) null else component::close,
            modifier,
        )
    }
}
