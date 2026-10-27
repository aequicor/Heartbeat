package io.aequicor.heartbeat.feature.appupdate.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppUpdatesComponent
import pro.respawn.flowmvi.dsl.collect

/** Compose adapter; navigation and the model remain outside the UI package. */
internal class AppUpdatesUiComponent(private val component: AppUpdatesComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val state by produceState(component.model.current.value, component.model) {
            component.model.store.collect { states.collect { value = it } }
        }
        val uriHandler = LocalUriHandler.current
        AppUpdatesContent(
            state,
            component.model.store::intent,
            onOpenPage = uriHandler::openUri,
            onBack = if (component.isEmbedded) null else component::close,
            modifier = modifier,
        )
    }
}
