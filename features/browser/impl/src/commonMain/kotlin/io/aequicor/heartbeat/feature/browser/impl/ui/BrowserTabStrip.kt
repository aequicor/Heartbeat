package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserTabUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_close_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_new_tab
import org.jetbrains.compose.resources.stringResource

/** The selected tab determines the visible group, including selection by agents or the inventory menu. */
@Composable
internal fun BrowserTabStrip(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId } ?: state.tabs.firstOrNull()
    val tabs = state.tabs.filter { it.groupId == selectedTab?.groupId }
    var revealRequest by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier) {
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
                val tabWidth = (maxWidth / tabs.size.coerceAtLeast(1)).coerceIn(
                    dimensions.browserTabMinWidth,
                    dimensions.browserTabMaxWidth,
                )
                HbRow(
                    Modifier.testTag("browser-group-${selectedTab?.groupId ?: "personal"}")
                        .selectableGroup().hbHorizontalScroll(rememberScrollState()),
                    gap = HbTheme.spacing.xxs,
                ) {
                    tabs.forEach { tab ->
                        key(tab.id) {
                            BrowserTab(
                                tab,
                                state.selectedTabId == tab.id,
                                tabWidth,
                                viewportWidth,
                                revealRequest,
                                onIntent,
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
    isSelected: Boolean,
    width: Dp,
    viewportWidth: Dp,
    revealRequest: Int,
    onIntent: (BrowserScreenIntent) -> Unit,
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
        modifier = Modifier.width(width).bringIntoViewRequester(bringIntoView).testTag("browser-tab-${tab.id}"),
        icon = HbIcons.Globe,
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
                { onIntent(BrowserScreenIntent.CloseTab(tab.id)) },
                Modifier.testTag("browser-close-tab-${tab.id}"),
                size = HbTheme.dimensions.compactControlHeight,
            )
        },
    )
}
