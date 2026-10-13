package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.navigation.compose.NavStack
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.BrowserPaneMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.SidePanelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.StudioNoDialog
import kotlinx.collections.immutable.toImmutableMap

/** Rendering adapter assembled by the route entry; presentation never imports Compose screens. */
internal class AiStudioUiComponent(private val component: AiStudioComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val workspace by component.workspace.stack.subscribeAsState()
        val isBrowserShown by component.showsBrowser.collectAsState(false)
        val isConnectionsShown by component.showsConnections.collectAsState(false)
        val isProfileSettingsShown by component.showsProfileSettings.collectAsState(false)
        val checklistHosts by component.checklists.collectAsState()
        val checklists = remember(checklistHosts) {
            checklistHosts.mapNotNull { (id, host) ->
                (host.stack.value.active.instance as? ComposableComponent)?.let { id to it }
            }.toMap().toImmutableMap()
        }
        val slotHosts by component.slots.collectAsState()
        val slots = remember(slotHosts) {
            slotHosts.mapNotNull { (key, host) ->
                (host.stack.value.active.instance as? ComposableComponent)?.let { key to it }
            }.toMap().toImmutableMap()
        }
        val cardHosts by component.cards.collectAsState()
        val cards = remember(cardHosts) {
            cardHosts.mapNotNull { (id, host) ->
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
        val browserMode by component.browserMode.collectAsState()
        val isBrowserOpen = browserMode != BrowserPaneMode.Hidden
        val exits = remember(
            component,
            isConnectionsShown,
            isProfileSettingsShown,
            isUnifiedSettingsShown,
            isBrowserShown,
            isBrowserOpen,
            questions,
            checklists,
            slots,
            cards,
        ) {
            StudioExits(
                onBack = component::close,
                onOpenToggles = component::openToggles,
                onOpenProfileSettings = if (isProfileSettingsShown) component::openProfileSettings else null,
                onOpenConnections = if (isConnectionsShown) component::openConnections else null,
                onOpenResearch = component::openResearch,
                onOpenBrowser = if (isBrowserShown) component::openBrowser else null,
                isBrowserOpen = isBrowserOpen,
                onCloseChatArea = component::closeChatArea,
                questions = questions,
                checklists = checklists,
                slots = slots,
                cards = cards,
                onOpenSettings = if (isUnifiedSettingsShown) component::openSettings else null,
            )
        }
        // Any entry above the studio's own chat (research) takes over the chat area; the sidebar
        // stays.
        val chatArea = workspace.active.instance as? ComposableComponent
        val side by component.sidePanel.collectAsState()
        val sideMode = when (side) {
            SidePanelUi.Browser -> browserMode
            SidePanelUi.Interface -> BrowserPaneMode.Panel
            SidePanelUi.None -> BrowserPaneMode.Hidden
        }
        StudioBrowserLayout(sideMode, modifier, browser = { SidePanel(component, side) }) {
            AiStudioScreen(component.model, exits, Modifier, chatArea = chatArea)
        }
        val dialogs by component.dialogs.stack.subscribeAsState()
        if (dialogs.active.instance !== StudioNoDialog) NavStack(component.dialogs)
    }
}

/** The browser, or the interface panel of the window on the assistant surface. */
@Composable
private fun SidePanel(component: AiStudioComponent, side: SidePanelUi) {
    if (side == SidePanelUi.Interface) {
        Box(
            Modifier.fillMaxSize().background(HbTheme.surfaces.assistant).padding(HbTheme.spacing.l)
                .testTag("studio-interface-panel"),
        ) { NavStack(component.interfacePanel) }
    } else {
        NavStack(component.browser)
    }
}
