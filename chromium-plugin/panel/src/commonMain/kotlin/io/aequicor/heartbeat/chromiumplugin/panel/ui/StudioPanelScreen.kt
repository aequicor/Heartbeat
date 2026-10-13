package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import io.aequicor.heartbeat.chromiumplugin.panel.resources.Res
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_busy
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_connecting
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_incompatible
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_new_view
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_too_large
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_unavailable
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelAction
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelIntent
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioPanelState
import io.aequicor.heartbeat.chromiumplugin.panel.store.StudioViewStatus
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenui.AiStudioContent
import io.aequicor.heartbeat.feature.aistudio.screenui.StudioHostCapabilities
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** The shared studio, its connection state, capture gestures and manual recovery after a replaced view. */
@Composable
internal fun StudioPanelScreen(
    store: Store<StudioPanelState, StudioPanelIntent, StudioPanelAction>,
    modifier: Modifier = Modifier,
) {
    val state by produceState(StudioPanelState(), store) { store.collect { states.collect { value = it } } }
    StudioPanelContent(state, store::intent, modifier)
}

/** Read-only stale frames keep their context without offering actions against a disconnected view. */
@Composable
internal fun StudioPanelContent(
    state: StudioPanelState,
    onIntent: (StudioPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    SideEffect(state.isInteractive) { if (!state.isInteractive) focus.clearFocus(force = true) }
    HbColumn(modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
        StudioViewNotice(state.remote.status, onIntent)
        StudioHostNotices(state, onIntent)
        if (state.remote.screen != null) {
            StudioViewContent(state, onIntent, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun StudioViewContent(
    state: StudioPanelState,
    onIntent: (StudioPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen = state.remote.screen ?: return
    val dispatch: (StudioIntent) -> Unit = {
        if (state.isInteractive) onIntent(StudioPanelIntent.Screen(it))
    }
    val semantics = if (state.isInteractive) Modifier else Modifier.clearAndSetSemantics { disabled() }
    Box(modifier.onPreviewKeyEvent { !state.isInteractive }) {
        AiStudioContent(
            screen,
            dispatch,
            screen.panelExits(dispatch),
            modifier = Modifier.fillMaxSize().then(semantics),
            host = StudioHostCapabilities(
                isAppUpdateAvailable = false,
                isNativeAttachmentInputAvailable = false,
                isAttachmentOpeningAvailable = false,
                isProjectAddingAvailable = false,
                isSplitAvailable = false,
                isOrganismLaunchingAvailable = false,
                onAttachPage = { if (state.isInteractive) onIntent(StudioPanelIntent.CapturePage(it)) },
            ),
        )
        if (!state.isInteractive) {
            Box(Modifier.matchParentSize().testTag("studio-read-only").consumeStaleInput())
        }
    }
}

/** A stale screen can be read but cannot change focus, edit text, scroll, or activate a command. */
private fun Modifier.consumeStaleInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}

@Composable
private fun StudioViewNotice(status: StudioViewStatus, onIntent: (StudioPanelIntent) -> Unit) {
    val label = when (status) {
        StudioViewStatus.Live -> return
        StudioViewStatus.Connecting -> Res.string.studio_connecting
        StudioViewStatus.Unavailable -> Res.string.studio_unavailable
        StudioViewStatus.Incompatible -> Res.string.studio_incompatible
        StudioViewStatus.Busy -> Res.string.studio_busy
        StudioViewStatus.TooLarge -> Res.string.studio_too_large
    }
    StudioNotice(
        stringResource(label),
        tone = HbTone.Neutral,
    ) {
        if (status == StudioViewStatus.TooLarge) {
            HbButton(
                stringResource(Res.string.studio_new_view),
                { onIntent(StudioPanelIntent.NewView) },
                modifier = Modifier.testTag("studio-new-view"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}
