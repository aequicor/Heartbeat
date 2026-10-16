package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbImage
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserFaviconImage
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserTabUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_close_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_loading
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_new_tab
import org.jetbrains.compose.resources.stringResource

/**
 * The selected tab determines the visible group, including selection by agents or the inventory menu. As in Chromium,
 * closing a tab keeps the others' width while the pointer stays over the strip, so the next close button lands under
 * it; leaving the strip lays the tabs out again.
 */
@Composable
internal fun BrowserTabStrip(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = state.visibleTabs
    val groupId = tabs.firstOrNull()?.groupId
    var revealRequest by remember { mutableIntStateOf(0) }
    var frozenTabWidth by remember { mutableStateOf<Dp?>(null) }
    var isPointerOver by remember { mutableStateOf(false) }
    val onPointerOver: (Boolean) -> Unit = remember {
        { isOver ->
            isPointerOver = isOver
            if (!isOver) frozenTabWidth = null
        }
    }
    BoxWithConstraints(modifier.onPointerOver(onPointerOver)) {
        val selectorWidth = (maxWidth / 2).coerceAtMost(
            HbTheme.dimensions.browserGroupLabelMaxWidth + HbTheme.dimensions.touchTarget,
        )
        HbRow(gap = HbTheme.spacing.xxs) {
            BrowserGroupMenu(
                state,
                onIntent = {
                    revealRequest++
                    onIntent(it)
                },
                modifier = Modifier.width(selectorWidth),
            )
            BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                val viewportWidth = maxWidth
                val dimensions = HbTheme.dimensions
                val tabWidth = frozenTabWidth ?: (maxWidth / tabs.size.coerceAtLeast(1)).coerceIn(
                    dimensions.browserTabMinWidth,
                    dimensions.browserTabMaxWidth,
                )
                val close: (String) -> Unit = { id ->
                    // Keyboard closes lay the strip out at once; only a pointer waits for the strip to be left.
                    if (isPointerOver) frozenTabWidth = tabWidth
                    onIntent(BrowserScreenIntent.CloseTab(id))
                }
                HbRow(
                    Modifier.testTag("browser-group-${groupId ?: "personal"}")
                        .selectableGroup().hbHorizontalScroll(rememberScrollState()),
                    gap = HbTheme.spacing.xxs,
                ) {
                    tabs.forEach { tab ->
                        key(tab.id) {
                            BrowserTab(
                                tab,
                                state.favicons[tab.faviconUrl],
                                state.selectedTabId == tab.id,
                                tabWidth,
                                viewportWidth,
                                revealRequest,
                                onIntent,
                                close,
                            )
                        }
                    }
                }
            }
            HbIconButton(
                HbIcons.Plus,
                stringResource(Res.string.browser_new_tab),
                { onIntent(BrowserScreenIntent.NewTab) },
                Modifier.testTag("browser-new-tab"),
            )
        }
    }
}

@Composable
private fun BrowserTab(
    tab: BrowserTabUi,
    favicon: BrowserFaviconImage?,
    isSelected: Boolean,
    width: Dp,
    viewportWidth: Dp,
    revealRequest: Int,
    onIntent: (BrowserScreenIntent) -> Unit,
    onClose: (String) -> Unit,
) {
    val title = tab.title.ifBlank { stringResource(Res.string.browser_new_tab) }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected, width, viewportWidth, revealRequest) {
        if (isSelected) {
            // Use the resized layout, and reveal even when the menu reselects the active tab.
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    HbNavigationItem(
        label = title,
        onClick = { onIntent(BrowserScreenIntent.SelectTab(tab.id)) },
        modifier = Modifier.width(width).bringIntoViewRequester(bringIntoView)
            .onMiddleClick { onClose(tab.id) }
            .testTag("browser-tab-${tab.id}"),
        // As in Chromium, the activity indicator takes the site icon's place while the page loads: a muted one turning
        // counter-clockwise while the server has not answered, then the accent one while the page arrives.
        icon = HbIcons.Globe.takeUnless { tab.isLoading || favicon != null },
        leadingContent = when {
            tab.isLoading -> {
                {
                    HbActivityIndicator(
                        Modifier.testTag(
                            (if (tab.isAwaitingResponse) "browser-tab-waiting-" else "browser-tab-loading-") + tab.id,
                        ),
                        contentDescription = stringResource(Res.string.browser_loading),
                        color = if (tab.isAwaitingResponse) HbTheme.colors.textSecondary else HbTheme.colors.brand,
                        size = HbTheme.dimensions.iconSize,
                        isCounterClockwise = tab.isAwaitingResponse,
                    )
                }
            }

            favicon != null -> {
                {
                    HbImage(
                        favicon.png,
                        contentDescription = "",
                        Modifier.size(HbTheme.dimensions.iconSize).testTag("browser-tab-favicon-${tab.id}"),
                    )
                }
            }

            else -> null
        },
        isSelected = isSelected,
        role = Role.Tab,
        selectedBackground = HbTheme.surfaces.backdrop,
        shape = RoundedCornerShape(
            topStart = HbTheme.dimensions.fieldCornerRadius,
            topEnd = HbTheme.dimensions.fieldCornerRadius,
        ),
        trailingContent = {
            HbIconButton(
                HbIcons.Close,
                stringResource(Res.string.browser_close_tab, title),
                { onClose(tab.id) },
                Modifier.testTag("browser-close-tab-${tab.id}"),
                size = HbTheme.dimensions.compactControlHeight,
            )
        },
    )
}

/** Reports whether a pointer is over this element, including over its children. */
@Composable
private fun Modifier.onPointerOver(onChange: (Boolean) -> Unit): Modifier {
    val currentOnChange by rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                when (awaitPointerEvent(PointerEventPass.Initial).type) {
                    PointerEventType.Enter, PointerEventType.Move -> currentOnChange(true)
                    PointerEventType.Exit -> currentOnChange(false)
                }
            }
        }
    }
}

/** Middle click closes a tab, as in desktop browsers; the press must also be released over the tab. */
@Composable
private fun Modifier.onMiddleClick(action: () -> Unit): Modifier {
    val currentAction by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var isArmed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> isArmed = event.buttons.isTertiaryPressed

                    PointerEventType.Exit -> isArmed = false

                    PointerEventType.Release -> {
                        if (isArmed) {
                            currentAction()
                            event.changes.forEach { it.consume() }
                        }
                        isArmed = false
                    }
                }
            }
        }
    }
}
