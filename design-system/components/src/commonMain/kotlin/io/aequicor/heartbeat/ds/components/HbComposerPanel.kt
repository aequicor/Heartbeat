package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

/** A stable editor and preferences toolbar; touch layouts give each preference group its own row. */
@Composable
internal fun ComposerPanelLayout(
    isFocused: Boolean,
    leadingContent: @Composable RowScope.() -> Unit,
    trailingContent: @Composable RowScope.() -> Unit,
    action: @Composable () -> Unit,
    editor: @Composable (Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(HbTheme.studioDimensions.cornerRadius)
    HbColumn(
        modifier = Modifier.fillMaxWidth()
            .hbFocusOutline(isFocused, shape, isTextInput = true)
            .pointerInput(Unit) { detectTapGestures { } }
            .background(HbTheme.studioColors.composer, shape)
            .border(HbTheme.dimensions.borderWidth, HbTheme.studioColors.outline, shape)
            .padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
    ) {
        editor(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.xs, vertical = HbTheme.spacing.xs))
        HbBoxWithConstraints(Modifier.fillMaxWidth()) {
            if (!HbTheme.studioDimensions.isDesktop && maxWidth < HbTheme.dimensions.compactBreakpoint) {
                HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
                    HbRow(
                        Modifier.fillMaxWidth().hbHorizontalScroll(rememberScrollState()),
                        gap = HbTheme.spacing.xs,
                        content = leadingContent,
                    )
                    ComposerActionRow(trailingContent, action)
                }
            } else {
                // Context controls keep their natural width (scrolling past 60%); model and send take the rest.
                val leadingMaxWidth = maxWidth * LEADING_MAX_FRACTION
                HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
                    HbRow(
                        Modifier.widthIn(max = leadingMaxWidth).hbHorizontalScroll(rememberScrollState()),
                        gap = HbTheme.spacing.xs,
                        content = leadingContent,
                    )
                    ComposerActionRow(trailingContent, action, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ComposerActionRow(
    trailingContent: @Composable RowScope.() -> Unit,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            HbRow(
                Modifier.hbHorizontalScroll(rememberScrollState(), reverseScrolling = true),
                gap = HbTheme.spacing.xs,
                content = trailingContent,
            )
        }
        action()
    }
}

private const val LEADING_MAX_FRACTION = 0.6f
