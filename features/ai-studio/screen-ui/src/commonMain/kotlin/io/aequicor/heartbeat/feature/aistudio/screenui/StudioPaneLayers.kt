package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * The bounded pane keeps its conversation, header, composer and summary in their original drawing and focus order.
 * A Start app receives the summary's measured band in the same pass, before either surface is placed. The slots
 * remain mounted across opening, resizing and content changes; only the conversation's available height changes.
 */
@Composable
internal fun PaneLayersLayout(
    reserveContextBand: Boolean,
    headerInset: Dp,
    conversation: @Composable () -> Unit,
    header: @Composable () -> Unit,
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    context: @Composable () -> Unit,
) {
    val gap = HbTheme.spacing.l
    Layout(
        content = {
            Box(Modifier.fillMaxSize()) { conversation() }
            Box { header() }
            Box(Modifier.fillMaxSize()) { composer() }
            Box { context() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val contextPlaceable = measurables[3].measure(loose)
        // Its outer height includes the header inset and upper gap; retain one gap below the card as well.
        val band = if (reserveContextBand && contextPlaceable.height > 0) {
            (contextPlaceable.height - headerInset.roundToPx() + gap.roundToPx()).coerceIn(0, constraints.maxHeight)
        } else {
            0
        }
        val conversationPlaceable = measurables[0].measure(constraints.offset(vertical = -band))
        val headerPlaceable = measurables[1].measure(loose)
        val composerPlaceable = measurables[2].measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            conversationPlaceable.placeRelative(0, band)
            headerPlaceable.placeRelative((constraints.maxWidth - headerPlaceable.width) / 2, 0)
            composerPlaceable.placeRelative(0, 0)
            contextPlaceable.placeRelative(constraints.maxWidth - contextPlaceable.width, 0)
        }
    }
}
