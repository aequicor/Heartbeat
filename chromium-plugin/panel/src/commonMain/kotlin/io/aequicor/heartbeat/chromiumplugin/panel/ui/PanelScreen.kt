package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.chromiumplugin.panel.store.PanelAction
import io.aequicor.heartbeat.chromiumplugin.panel.store.PanelIntent
import io.aequicor.heartbeat.chromiumplugin.panel.store.PanelState
import io.aequicor.heartbeat.chromiumplugin.panel.store.isAgentControlOffered
import io.aequicor.heartbeat.chromiumplugin.panel.store.notice
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect

/** The side panel bound to its shell store; [content] fills the space under the link notice and consent. */
@Composable
internal fun PanelApp(
    store: Store<PanelState, PanelIntent, PanelAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val state by produceState(PanelState(), store) { store.collect { states.collect { value = it } } }
    PanelContent(state, store::intent, modifier, content)
}

/** Stateless content of [PanelApp]. */
@Composable
internal fun PanelContent(
    state: PanelState,
    onIntent: (PanelIntent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    HbColumn(modifier.fillMaxSize().background(HbTheme.surfaces.backdrop), gap = HbTheme.spacing.none) {
        PanelBanner(state.notice)
        AgentControlSection(
            agent = state.agent,
            isConsentShown = state.isAgentControlOffered,
            onConsent = { onIntent(PanelIntent.SetAgentControl(it)) },
            onRevoke = { onIntent(PanelIntent.RevokeTabs) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
