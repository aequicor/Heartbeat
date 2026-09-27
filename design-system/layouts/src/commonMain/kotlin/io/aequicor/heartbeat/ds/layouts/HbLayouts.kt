package io.aequicor.heartbeat.ds.layouts

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Constraint-aware container for adapting slot content to the available window space. */
@Composable
@NonRestartableComposable
public fun HbBoxWithConstraints(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier, content = content)
}

/** A vertical layout with theme spacing and the standard [ColumnScope] contract. */
@Composable
public fun HbColumn(
    modifier: Modifier = Modifier,
    gap: Dp = HbTheme.spacing.l,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** A horizontal layout with theme spacing and the standard [RowScope] contract. */
@Composable
public fun HbRow(
    modifier: Modifier = Modifier,
    gap: Dp = HbTheme.spacing.l,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/** Wrapping layout for controls and chips, using the same theme gap in both axes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun HbFlowRow(
    modifier: Modifier = Modifier,
    gap: Dp = HbTheme.spacing.s,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Virtualized vertical layout; callers retain keys, scrolling and item composition control. */
@Composable
public fun HbLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gap: Dp = HbTheme.spacing.l,
    contentPadding: PaddingValues = PaddingValues(HbTheme.spacing.l),
    reverseLayout: Boolean = false,
    showScrollbar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = if (showScrollbar) modifier.hbScrollbars(state, Orientation.Vertical, reverseLayout) else modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Virtualized horizontal layout with an automatically hidden scrollbar and stable caller-provided keys. */
@Composable
public fun HbLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gap: Dp = HbTheme.spacing.l,
    contentPadding: PaddingValues = PaddingValues(HbTheme.spacing.l),
    reverseLayout: Boolean = false,
    showScrollbar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = if (showScrollbar) modifier.hbScrollbars(state, Orientation.Horizontal, reverseLayout) else modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * Shows navigation beside content on wide windows and above content on compact screens.
 * Both slots remain accessible when resized, including on mobile devices.
 */
@Composable
public fun HbAdaptivePane(
    sidebar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    compactSidebar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val currentSidebar by rememberUpdatedState(sidebar)
    val currentContent by rememberUpdatedState(content)
    val movableSidebar = remember { movableContentOf { currentSidebar() } }
    val movableContent = remember { movableContentOf { currentContent() } }
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= HbTheme.dimensions.compactBreakpoint) {
            HbRow(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.width(HbTheme.dimensions.sidebarWidth)) { movableSidebar() }
                Box(modifier = Modifier.weight(1f)) { movableContent() }
            }
        } else {
            HbColumn(modifier = Modifier.fillMaxSize()) {
                (compactSidebar ?: movableSidebar)()
                Box(modifier = Modifier.weight(1f)) { movableContent() }
            }
        }
    }
}
