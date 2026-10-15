package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.AppHostComponent

/** The pane-owned app route stays mounted while its compact entries are recycled with the transcript. */
internal class AppUiComponent(private val component: AppHostComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        AppScreen(component.model, modifier, rememberWebDocuments(component.documents.documents))
    }
}
