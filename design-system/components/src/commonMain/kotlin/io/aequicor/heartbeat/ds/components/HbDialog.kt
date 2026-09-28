package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Dialog")

/**
 * The one modal surface of the design system: a dialog on wide windows and a full-width sheet on compact ones.
 * A [title], scrollable [content] and [actions] aligned to the trailing edge (primary action last). Esc, the
 * system back action and a click outside call [onDismissRequest]; the caller decides whether to close.
 * The surface is flat with the popup shadow — the only elevated surface of the style — and no blur.
 */
@Composable
public fun HbDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val dimensions = HbTheme.dimensions
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val isSheet = windowWidth < dimensions.compactBreakpoint
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        // Wait until the focus target is attached and laid out.
        withFrameNanos { }
        focus.requestFocus()
    }
    Dialog(
        onDismissRequest = {
            log.i { "dialog dismissed" }
            dismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        HbColumn(
            modifier
                .then(if (isSheet) Modifier.fillMaxWidth() else Modifier)
                .widthIn(min = dimensions.dialogMinWidth, max = if (isSheet) windowWidth else dimensions.dialogMaxWidth)
                .padding(HbTheme.spacing.l)
                .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.large)
                .semantics { paneTitle = title }
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                        log.i { "dialog dismissed with Escape" }
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
                // The panel takes focus on open so Esc and Tab work before any control is focused.
                .focusRequester(focus)
                .focusable()
                .padding(HbTheme.spacing.xl),
            gap = HbTheme.spacing.l,
        ) {
            HbText(
                title,
                Modifier.semantics { heading() },
                style = HbTheme.typography.title.copy(fontWeight = FontWeight.SemiBold),
            )
            HbColumn(
                Modifier.weight(1f, fill = false).hbVerticalScroll(rememberScrollState()),
                gap = HbTheme.spacing.m,
                content = content,
            )
            HbRow(
                Modifier.align(Alignment.End),
                gap = HbTheme.spacing.s,
                content = actions,
            )
        }
    }
}
