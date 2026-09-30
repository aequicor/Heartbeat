package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Composer-anchored information surface; the focusable body also supports keyboard scrolling. */
@Composable
internal fun ComposerUsagePopup(
    label: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val anchor = LocalComposerAnchor.current?.value
    val density = LocalDensity.current
    val dimensions = HbTheme.dimensions
    val gutter = maxOf(dimensions.composerMenuGutter, dimensions.popupShadowRadius + dimensions.popupShadowOffset)
    val window = LocalWindowInfo.current.containerSize
    val availableWidth = with(density) { window.width.toDp() } - gutter * 2
    val availableHeight = with(density) { (anchor?.top ?: window.height).toDp() } -
        gutter - dimensions.composerMenuOffset
    val width = minOf(dimensions.composerMenuMaxWidth, availableWidth.coerceAtLeast(dimensions.touchTarget))
    val height = minOf(dimensions.composerMenuMaxHeight, availableHeight.coerceAtLeast(dimensions.touchTarget))
    val position = with(density) {
        remember(anchor, gutter, dimensions.composerMenuOffset, this) {
            ComposerPopupPosition(anchor, gutter.roundToPx(), dimensions.composerMenuOffset.roundToPx())
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        withFrameNanos { }
        focus.requestFocus()
    }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(modifier.padding(gutter)) {
            HbColumn(
                Modifier.width(width).heightIn(max = height)
                    .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.medium)
                    .semantics { paneTitle = label }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    }
                    .focusRequester(focus).focusable()
                    .hbVerticalScroll(rememberScrollState())
                    .padding(HbTheme.spacing.l),
                gap = HbTheme.spacing.l,
                content = content,
            )
        }
    }
}
