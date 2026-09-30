package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

/** A stable editor and preferences toolbar; narrow layouts give each preference group its own row. */
@Composable
internal fun ComposerPanelLayout(
    isFocused: Boolean,
    leadingContent: @Composable RowScope.() -> Unit,
    trailingContent: @Composable RowScope.() -> Unit,
    action: @Composable () -> Unit,
    editor: @Composable (Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(HbTheme.dimensions.cornerRadius)
    HbColumn(
        modifier = Modifier.fillMaxWidth()
            .hbFocusOutline(isFocused, shape, isTextInput = true)
            .pointerInput(Unit) { detectTapGestures { } }
            .background(HbTheme.surfaces.composer, shape)
            .border(HbTheme.dimensions.borderWidth, HbTheme.surfaces.outline, shape)
            .padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
    ) {
        editor(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.xs, vertical = HbTheme.spacing.xs))
        HbBoxWithConstraints(Modifier.fillMaxWidth()) {
            val toolbarBreakpoint = if (HbTheme.dimensions.isDesktop) {
                HbTheme.dimensions.composerToolbarBreakpoint
            } else {
                HbTheme.dimensions.compactBreakpoint
            }
            val isNarrow = maxWidth < toolbarBreakpoint
            val gap = HbTheme.spacing.xs
            // Both groups keep the same parents and composition when their placement changes.
            Layout(
                content = {
                    HbRow(
                        Modifier.hbHorizontalScroll(rememberScrollState()),
                        gap = gap,
                        content = leadingContent,
                    )
                    ComposerActionRow(trailingContent, action)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { measurables, constraints -> measurePanelToolbar(measurables, constraints, isNarrow, gap) }
        }
    }
}

private fun MeasureScope.measurePanelToolbar(
    measurables: List<Measurable>,
    constraints: Constraints,
    isNarrow: Boolean,
    gap: Dp,
): MeasureResult {
    val gapPx = gap.roundToPx()
    val width = constraints.maxWidth
    val leadingWidth = if (isNarrow) width else (width * LEADING_MAX_FRACTION).toInt()
    val leading = measurables[0].measure(
        constraints.copy(minWidth = if (isNarrow) width else 0, maxWidth = leadingWidth, minHeight = 0),
    )
    val remainingHeight = constraints.remainingHeightAfter(leading.height)
    val rowGap = if (isNarrow) minOf(gapPx, remainingHeight) else gapPx
    val trailingMaxHeight = if (isNarrow) {
        constraints.remainingHeightAfter(leading.height + rowGap)
    } else {
        constraints.maxHeight
    }
    val trailingWidth = if (isNarrow) width else (width - leading.width - gapPx).coerceAtLeast(0)
    val trailing = measurables[1].measure(
        constraints.copy(
            minWidth = trailingWidth,
            maxWidth = trailingWidth,
            minHeight = 0,
            maxHeight = trailingMaxHeight,
        ),
    )
    val height = if (isNarrow) leading.height + rowGap + trailing.height else maxOf(leading.height, trailing.height)
    return layout(width, constraints.constrainHeight(height)) {
        placeToolbarGroups(leading, trailing, isNarrow, rowGap, height)
    }
}

/** Preserve unbounded height inside scrolling heroes instead of producing an unsupported finite constraint. */
private fun Constraints.remainingHeightAfter(used: Int): Int =
    if (hasBoundedHeight) (maxHeight - used).coerceAtLeast(0) else maxHeight

private fun Placeable.PlacementScope.placeToolbarGroups(
    leading: Placeable,
    trailing: Placeable,
    isNarrow: Boolean,
    gap: Int,
    height: Int,
) {
    if (isNarrow) {
        leading.placeRelative(0, 0)
        trailing.placeRelative(0, leading.height + gap)
    } else {
        leading.placeRelative(0, (height - leading.height) / 2)
        trailing.placeRelative(leading.width + gap, (height - trailing.height) / 2)
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
