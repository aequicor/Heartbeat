package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.navigation.compose.NavStack
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.BrowserPaneMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.SidePanelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.StudioNoDialog
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.interface_panel
import kotlinx.collections.immutable.toImmutableMap
import org.jetbrains.compose.resources.stringResource

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
        val side by component.sidePanel.collectAsState()
        // The rail marks the browser only while it is the panel in front.
        val isBrowserOpen = browserMode != BrowserPaneMode.Hidden && side == SidePanelUi.Browser
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

/** The browser, or the interface panel of the window drawn like the browser panel, within the window insets. */
@Composable
private fun SidePanel(component: AiStudioComponent, side: SidePanelUi) {
    if (side == SidePanelUi.Interface) {
        val panel by component.interfacePanel.collectAsState()
        val title = stringResource(Res.string.interface_panel)
        Box(
            Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).safeDrawingPadding().imePadding()
                .semantics { paneTitle = title }.testTag("studio-interface-panel"),
        ) { panel?.let { NavStack(it) } }
    } else {
        NavStack(component.browser)
    }
}
