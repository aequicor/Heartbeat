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

/** Upper width bound of [HbDialog]: [Regular] for forms and confirmations, [Wide] for content that needs room. */
public enum class HbDialogWidth {
    Regular,
    Wide,
}

/**
 * The one modal surface of the design system: a dialog on wide windows and a full-width sheet on compact ones.
 * A [title], scrollable [content] and [actions] aligned to the trailing edge (primary action last). Esc, the
 * system back action and a click outside call [onDismissRequest]; the caller decides whether to close.
 * The surface is flat with the popup shadow — the only elevated surface of the style — and no blur.
 * With [isContentScrollable] false the content area is still bounded by the window but does not scroll itself,
 * so content with its own scrolling (for example on both axes) is not nested inside a vertical scroll.
 */
@Composable
public fun HbDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    width: HbDialogWidth = HbDialogWidth.Regular,
    isContentScrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val dimensions = HbTheme.dimensions
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val isSheet = windowWidth < dimensions.compactBreakpoint
    val maxWidth = when {
        isSheet -> windowWidth
        width == HbDialogWidth.Wide -> dimensions.dialogWideMaxWidth
        else -> dimensions.dialogMaxWidth
    }
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
                .widthIn(min = dimensions.dialogMinWidth, max = maxWidth)
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
            val body = Modifier.weight(1f, fill = false)
            HbColumn(
                if (isContentScrollable) body.hbVerticalScroll(rememberScrollState()) else body,
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
