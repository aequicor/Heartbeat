package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.EditorComponent

/** Route rendering adapter keeping Compose screens out of the presentation component. */
internal class EditorUiComponent(private val component: EditorComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        EditorScreen(
            component.model,
            onBack = component::close,
            modifier = modifier,
            documents = rememberWebDocuments(component.documents.documents),
        )
    }
}
