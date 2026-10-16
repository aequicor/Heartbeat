package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPopover
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserShortcut
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserZoomDirection
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_zoom
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_zoom_in
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_zoom_out
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_zoom_reset
import org.jetbrains.compose.resources.stringResource

/** The popup belongs to the current tab and address; navigating or resetting removes the old anchor. */
@Composable
internal fun BrowserZoomControl(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isOpen by remember(state.selectedTabId, state.url, state.isZoomEnabled) { mutableStateOf(false) }
    if (!state.isZoomEnabled || (state.zoomPercent == 100 && !isOpen)) return
    val trigger = remember { FocusRequester() }
    val label = stringResource(Res.string.browser_zoom, state.zoomPercent)
    val zoom: (BrowserZoomDirection) -> Unit = {
        if (it == BrowserZoomDirection.Reset) isOpen = false
        onIntent(BrowserScreenIntent.Shortcut(BrowserShortcut.Zoom(it)))
    }
    Box(modifier) {
        HbIconButton(
            if (state.zoomPercent > 100) HbIcons.ZoomIn else HbIcons.ZoomOut,
            label,
            { isOpen = !isOpen },
            Modifier.testTag("browser-zoom").focusRequester(trigger),
            isSelected = isOpen,
        )
        HbPopover(
            isOpen,
            {
                isOpen = false
                trigger.requestFocus()
            },
            label,
            Modifier.onPreviewKeyEvent { event ->
                val command = event.browserShortcut() as? BrowserShortcut.Zoom
                if (command != null && event.type == KeyEventType.KeyDown) {
                    zoom(command.direction)
                    true
                } else {
                    false
                }
            },
        ) {
            HbRow {
                HbText("${state.zoomPercent}%", Modifier.testTag("browser-zoom-percent"))
                HbIconButton(
                    HbIcons.Minus,
                    stringResource(Res.string.browser_zoom_out),
                    { zoom(BrowserZoomDirection.Out) },
                    Modifier.testTag("browser-zoom-out"),
                    enabled = state.isZoomOutAvailable,
                )
                HbIconButton(
                    HbIcons.Plus,
                    stringResource(Res.string.browser_zoom_in),
                    { zoom(BrowserZoomDirection.In) },
                    Modifier.testTag("browser-zoom-in"),
                    enabled = state.isZoomInAvailable,
                )
                HbButton(
                    stringResource(Res.string.browser_zoom_reset),
                    { zoom(BrowserZoomDirection.Reset) },
                    Modifier.testTag("browser-zoom-reset"),
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}
