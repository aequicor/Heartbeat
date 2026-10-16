package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Controlled anchored sheet for interactive content. Place beside its trigger in a Box. Every kit shares the
 * Foundation implementation to preserve arbitrary content and keyboard semantics. Escape and an outside click
 * dismiss; on desktop the popup also covers heavyweight native browser surfaces. Tab reaches the content's controls.
 * The owner restores focus to its trigger when dismissing, if that trigger still exists.
 */
@Composable
public fun HbPopover(
    isExpanded: Boolean,
    onDismiss: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!isExpanded) return
    val gap = with(LocalDensity.current) { HbTheme.spacing.xs.roundToPx() }
    val position = remember(gap) { HbMenuPosition(gap) }
    HbMenuPopup(position, onDismiss) {
        val focus = remember { FocusRequester() }
        SideEffect(Unit) { focus.requestFocus() }
        Box(
            modifier.focusRequester(focus).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }.focusable().semantics { paneTitle = label }
                .background(HbTheme.colors.surfaceElevated, HbTheme.shapes.medium)
                .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.medium)
                .padding(HbTheme.spacing.m),
        ) { content() }
    }
}
