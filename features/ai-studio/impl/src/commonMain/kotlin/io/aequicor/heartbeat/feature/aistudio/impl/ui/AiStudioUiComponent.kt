package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.navigation.compose.NavStack
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.StudioNoDialog
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.browser_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.research_mode
import kotlinx.collections.immutable.toImmutableMap
import org.jetbrains.compose.resources.stringResource

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AiStudioUiComponent(private val component: AiStudioComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val workspace by component.workspace.stack.subscribeAsState()
        val isBrowserShown by component.showsBrowser.collectAsState(false)
        val chatAreaTitle = stringResource(
            if (component.isBrowserActive()) Res.string.browser_mode else Res.string.research_mode,
        )
        val isConnectionsShown by component.showsConnections.collectAsState(false)
        val isProfileSettingsShown by component.showsProfileSettings.collectAsState(false)
        val checklistHosts by component.checklists.collectAsState()
        val checklists = remember(checklistHosts) {
            checklistHosts.mapNotNull { (id, host) ->
                (host.stack.value.active.instance as? ComposableComponent)?.let { id to it }
            }.toMap().toImmutableMap()
        }
        val hosts by component.questions.collectAsState()
        // Each host has one fixed entry, so its component never changes.
        val questions = remember(hosts) {
            hosts.mapNotNull { (session, host) ->
                (host.stack.value.active.instance as? ComposableComponent)?.let { session to it }
            }.toMap().toImmutableMap()
        }
        val isUnifiedSettingsShown by component.showsUnifiedSettings.collectAsState(false)
        val exits = remember(
            component,
            isConnectionsShown,
            isProfileSettingsShown,
            isUnifiedSettingsShown,
            isBrowserShown,
            chatAreaTitle,
            questions,
            checklists,
        ) {
            StudioExits(
                onBack = component::close,
                onOpenToggles = component::openToggles,
                onOpenProfileSettings = if (isProfileSettingsShown) component::openProfileSettings else null,
                onOpenConnections = if (isConnectionsShown) component::openConnections else null,
                onOpenResearch = component::openResearch,
                onOpenBrowser = if (isBrowserShown) component::openBrowser else null,
                onCloseChatArea = component::closeChatArea,
                chatAreaTitle = chatAreaTitle,
                questions = questions,
                checklists = checklists,
                onOpenSettings = if (isUnifiedSettingsShown) component::openSettings else null,
            )
        }
        // Any entry above the studio's own chat (research) takes over the chat area; the sidebar stays.
        val chatArea = workspace.active.instance as? ComposableComponent
        AiStudioScreen(component.model, exits, modifier, chatArea = chatArea)
        val dialogs by component.dialogs.stack.subscribeAsState()
        if (dialogs.active.instance !== StudioNoDialog) NavStack(component.dialogs)
    }
}
