package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import kotlinx.coroutines.awaitCancellation
import pro.respawn.flowmvi.dsl.collect

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AiStudioUiComponent(private val component: AiStudioComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        LaunchedEffect(component.model) { component.model.store.collect { awaitCancellation() } }
        AiStudioScreen(component::close, modifier)
    }
}
