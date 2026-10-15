package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_close
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_context_panel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The retained slot is mounted in exactly one responsive location; its renderer owns its single scroll. */
@Composable
internal fun PaneContextPanel(
    slot: StudioEmbed?,
    isDocked: Boolean,
    isOpen: Boolean,
    onDismiss: () -> Unit,
    paneId: Int,
    topInset: Dp,
    paneHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (slot == null) return
    if (isDocked) {
        slot.Content(
            modifier.padding(top = topInset, end = HbTheme.spacing.l)
                .width(HbTheme.dimensions.inspectorPanelWidth)
                .heightIn(max = (paneHeight - topInset - HbTheme.spacing.l).coerceAtLeast(HbTheme.spacing.none))
                .testTag("pane-context-panel-$paneId"),
        )
    } else if (isOpen) {
        HbDialog(
            title = stringResource(Res.string.pane_context_panel),
            onDismissRequest = onDismiss,
            isContentScrollable = false,
            isCompact = true,
            isInWindow = true,
            headerActions = { HbIconButton(HbIcons.Close, stringResource(Res.string.pane_close), onDismiss) },
        ) {
            slot.Content(Modifier.fillMaxWidth().testTag("pane-context-dialog-$paneId"))
        }
    }
}

/** A context panel never squeezes the transcript or its composer below the regular pane minimum. */
@Composable
@ReadOnlyComposable
internal fun contextPanelFits(width: Dp): Boolean = width >= HbTheme.dimensions.paneMinWidth +
    HbTheme.dimensions.messagePadding * 2 + HbTheme.dimensions.inspectorPanelWidth + HbTheme.spacing.l * 2

/** Local presentation of a retained context slot; opening it never changes the chat or its draft. */
internal data class ContextPanelPresentation(
    val isDocked: Boolean,
    val inset: Dp,
    val isOpen: Boolean,
    val focus: FocusRequester,
    val open: (() -> Unit)?,
    val dismiss: () -> Unit,
)

@Composable
internal fun rememberPaneContextPanel(
    content: PaneContent,
    slot: StudioEmbed?,
    isCompact: Boolean,
    width: Dp,
): ContextPanelPresentation {
    var isOpen by remember(content.pane.sessionId, content.pane.projectId, content.harnesses) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isDocked = slot != null && !isCompact && contextPanelFits(width)
    val inset = if (isDocked) HbTheme.dimensions.inspectorPanelWidth + HbTheme.spacing.l * 2 else HbTheme.spacing.none
    val isContextFocusRestorable by rememberUpdatedState(slot != null && !isDocked)
    SideEffect(isDocked, slot) { if (isDocked || slot == null) isOpen = false }
    val dismiss = {
        isOpen = false
        scope.launch {
            withFrameNanos { }
            if (isContextFocusRestorable) focus.requestFocus()
        }
        Unit
    }
    return ContextPanelPresentation(
        isDocked,
        inset,
        isOpen,
        focus,
        open = {
            // A macOS mouse click need not focus the button; save it before the modal captures background focus.
            focus.requestFocus()
            isOpen = true
        }.takeIf { slot != null && !isDocked },
        dismiss = dismiss,
    )
}
