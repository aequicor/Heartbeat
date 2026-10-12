package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.layouts.LocalPaneExpansionAvailable
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.BrowserPaneMode
import kotlin.math.roundToInt

/**
 * One stable composition slot for the browser and one for the [chat]: resizing and expanding never replace the
 * browser's native viewport, and the chat stays composed (with its remembered state) while the expanded browser
 * covers the workspace — it is measured at the full width but not placed, so it is neither drawn nor hit.
 * A panel never narrows the chat below the pane minimum: the browser share is limited by the available width.
 */
@Composable
internal fun StudioBrowserLayout(
    mode: BrowserPaneMode,
    modifier: Modifier = Modifier,
    browser: @Composable () -> Unit = {},
    chat: @Composable () -> Unit,
) {
    var fraction by rememberSaveable { mutableFloatStateOf(DEFAULT_PANE_FRACTION) }
    var widthPixels by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val dimensions = HbTheme.dimensions
    val dividerWidth = HbTheme.spacing.s
    val isExpansionAvailable = with(density) { widthPixels.toDp() } >= dimensions.browserSplitMinWidth
    val chatMinPixels = with(density) { (dimensions.paneMinWidth + dividerWidth).roundToPx() }
    val splitMinPixels = with(density) { dimensions.browserSplitMinWidth.roundToPx() }
    val isChatCovered = mode == BrowserPaneMode.Expanded ||
        (mode == BrowserPaneMode.Panel && widthPixels > 0 && !isExpansionAvailable)
    Layout(
        contents = listOf(
            { CoverableChat(isChatCovered, chat) },
            {
                if (mode == BrowserPaneMode.Panel && isExpansionAvailable) {
                    BrowserPaneDivider(dividerWidth) { delta ->
                        fraction = resizedFraction(fraction, delta, widthPixels, chatMinPixels)
                    }
                }
            },
            { BrowserSlot(mode, isExpansionAvailable, browser) },
        ),
        modifier = modifier.fillMaxSize().onSizeChanged { widthPixels = it.width },
        measurePolicy = browserLayoutPolicy(mode, { fraction }, splitMinPixels, chatMinPixels),
    )
}

@Composable
private fun BrowserSlot(mode: BrowserPaneMode, isExpansionAvailable: Boolean, browser: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("browser-panel")) {
        if (mode != BrowserPaneMode.Hidden) {
            CompositionLocalProvider(LocalPaneExpansionAvailable provides isExpansionAvailable) { browser() }
        }
    }
}

/**
 * Places chat, divider and browser slots. Below [splitMin] pixels a panel is shown expanded. An expanded browser
 * leaves the chat measured at the full width but unplaced, so its layout never collapses while it is covered.
 */
private fun browserLayoutPolicy(mode: BrowserPaneMode, fraction: () -> Float, splitMin: Int, chatMin: Int) =
    MultiContentMeasurePolicy { (chatSlot, dividerSlot, browserSlot), constraints ->
        val total = constraints.maxWidth
        val height = constraints.maxHeight
        val effective = if (mode == BrowserPaneMode.Panel && total < splitMin) BrowserPaneMode.Expanded else mode
        val divider = if (effective == BrowserPaneMode.Panel) {
            dividerSlot.map { it.measure(Constraints(maxWidth = total, minHeight = height, maxHeight = height)) }
        } else {
            emptyList()
        }
        val browserPixels = effective.browserWidth(total, fraction(), chatMin)
        val sideBySide = total - browserPixels - divider.sumOf { it.width }
        val chatPixels = if (effective == BrowserPaneMode.Expanded) total else sideBySide
        val chatPlaceables = chatSlot.map { it.measure(Constraints.fixed(chatPixels.coerceAtLeast(0), height)) }
        val browserPlaceables = browserSlot.map { it.measure(Constraints.fixed(browserPixels, height)) }
        layout(total, height) {
            if (effective != BrowserPaneMode.Expanded) chatPlaceables.forEach { it.place(0, 0) }
            divider.forEach { it.place(chatPixels, 0) }
            browserPlaceables.forEach { it.place(total - browserPixels, 0) }
        }
    }

/**
 * The chat slot; while [isCovered] it is hidden from accessibility services, and focus inside it is released whenever
 * it lands there — also when chat code requests it programmatically — so keys typed for the browser never reach it.
 */
@Composable
private fun CoverableChat(isCovered: Boolean, chat: @Composable () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isReleaseNeeded = isCovered && isFocused
    SideEffect {
        if (isReleaseNeeded) focusManager.clearFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (isCovered) Modifier.clearAndSetSemantics { } else Modifier)
            .onFocusChanged { isFocused = it.hasFocus },
    ) { chat() }
}

private fun BrowserPaneMode.browserWidth(total: Int, fraction: Float, chatMin: Int): Int = when (this) {
    BrowserPaneMode.Hidden -> 0
    BrowserPaneMode.Panel -> (total * panelFraction(fraction, total, chatMin)).roundToInt()
    BrowserPaneMode.Expanded -> total
}

/** The browser share of [total] pixels: never below the minimum, and never squeezing the chat below [chatMin]. */
private fun panelFraction(fraction: Float, total: Int, chatMin: Int): Float {
    val chatLimit = if (total > 0) (total - chatMin).toFloat() / total else MIN_PANE_FRACTION
    return fraction.coerceIn(MIN_PANE_FRACTION, chatLimit.coerceIn(MIN_PANE_FRACTION, MAX_PANE_FRACTION))
}

private fun resizedFraction(current: Float, delta: Float, width: Int, chatMin: Int): Float =
    if (width > 0) panelFraction(panelFraction(current, width, chatMin) - delta / width, width, chatMin) else current

@Composable
private fun BrowserPaneDivider(width: Dp, onResize: (Float) -> Unit) {
    Box(
        Modifier.fillMaxHeight()
            .width(width)
            .background(HbTheme.colors.outline)
            .testTag("browser-resize")
            .draggable(rememberDraggableState(onResize), Orientation.Horizontal),
    )
}

private const val DEFAULT_PANE_FRACTION = 0.45f
private const val MIN_PANE_FRACTION = 0.25f
private const val MAX_PANE_FRACTION = 0.75f
