package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import kotlinx.collections.immutable.toImmutableMap

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AiStudioUiComponent(private val component: AiStudioComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val isConnectionsShown by component.showsConnections.collectAsState(false)
        val isProfileSettingsShown by component.showsProfileSettings.collectAsState(false)
        val hosts by component.questions.collectAsState()
        // Each host has one fixed entry, so its component never changes.
        val questions = remember(hosts) {
            hosts.mapNotNull { (session, host) ->
                (host.stack.value.active.instance as? ComposableComponent)?.let { session to it }
            }.toMap().toImmutableMap()
        }
        val isUnifiedSettingsShown by component.showsUnifiedSettings.collectAsState(false)
        val exits = remember(component, isConnectionsShown, isProfileSettingsShown, isUnifiedSettingsShown, questions) {
            StudioExits(
                onBack = component::close,
                onOpenToggles = component::openToggles,
                onOpenProfileSettings = if (isProfileSettingsShown) component::openProfileSettings else null,
                onOpenConnections = if (isConnectionsShown) component::openConnections else null,
                onOpenResearch = component::openResearch,
                questions = questions,
                onOpenSettings = if (isUnifiedSettingsShown) component::openSettings else null,
            )
        }
        val workspace by component.workspace.stack.subscribeAsState()
        // Any entry above the studio's own chat (research) takes over the chat area; the sidebar stays.
        val chatArea = workspace.active.instance as? ComposableComponent
        AiStudioScreen(component.model, exits, modifier, chatArea = chatArea)
    }
}
