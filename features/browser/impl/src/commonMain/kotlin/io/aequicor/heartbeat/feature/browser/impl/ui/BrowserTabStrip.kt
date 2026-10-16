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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
 * closing a tab keeps the others' width while a mouse stays over the strip, so the next close button lands under it;
 * leaving the strip, a new tab or another group lays the tabs out again. Only a mouse freezes the width, since a
 * finger reports no exit; a touchscreen the desktop reports as a mouse behaves like one.
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
    var frozen by remember { mutableStateOf<FrozenTabWidth?>(null) }
    var isMouseOver by remember { mutableStateOf(false) }
    val onMouseOver: (Boolean) -> Unit = remember {
        { isOver ->
            isMouseOver = isOver
            if (!isOver) frozen = null
        }
    }
    val isFreezeStale = frozen?.let { it.groupId != groupId || tabs.size > it.maxTabs } == true
    // A freeze that no longer applies is dropped, so it cannot return when the count shrinks or the group is back.
    if (isFreezeStale) SideEffect { frozen = null }
    BoxWithConstraints(modifier.onMouseOver(onMouseOver)) {
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
                val tabWidth = frozen?.takeIf { it.groupId == groupId && tabs.size <= it.maxTabs }?.width
                    ?: (maxWidth / tabs.size.coerceAtLeast(1)).coerceIn(
                        dimensions.browserTabMinWidth,
                        dimensions.browserTabMaxWidth,
                    )
                val close: (String) -> Unit = { id ->
                    // Only a mouse waits for the strip to be left; the freeze lasts while tabs only close.
                    if (isMouseOver) frozen = FrozenTabWidth(tabWidth, groupId, tabs.size - 1)
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
                        // Only the selected tab announces its loading; background tabs would flood a screen reader.
                        contentDescription = stringResource(Res.string.browser_loading).takeIf { isSelected },
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

/** The width tabs keep in [groupId] while no more than [maxTabs] of them are shown. */
private data class FrozenTabWidth(val width: Dp, val groupId: String?, val maxTabs: Int)

/** Reports whether a mouse is over this element, including over its children; any other pointer counts as not. */
@Composable
private fun Modifier.onMouseOver(onChange: (Boolean) -> Unit): Modifier {
    val currentOnChange by rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val isMouse = event.changes.all { it.type == PointerType.Mouse }
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> currentOnChange(isMouse)
                    PointerEventType.Exit -> currentOnChange(false)
                }
            }
        }
    }
}

/**
 * Middle click closes a tab, as in desktop browsers: the middle button must be pressed and released over the tab, with
 * no other button involved.
 */
@Composable
private fun Modifier.onMiddleClick(action: () -> Unit): Modifier {
    val currentAction by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var isArmed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> {
                        val buttons = event.buttons
                        isArmed = buttons.isTertiaryPressed && !buttons.isPrimaryPressed && !buttons.isSecondaryPressed
                    }

                    PointerEventType.Exit -> isArmed = false

                    PointerEventType.Release -> {
                        if (isArmed && !event.buttons.isTertiaryPressed) {
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
