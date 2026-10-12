package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.accessibleContentColor
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserTabUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_all_tabs
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_close_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_new_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_session_group
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_user_group
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Shrinking tabs scroll at their minimum width; inventory and creation stay outside the scroll area. */
@Composable
internal fun BrowserTabStrip(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    val groups = state.tabs.groupBy { it.groupId }
    HbRow(
        Modifier.fillMaxWidth().background(HbTheme.surfaces.sidebar)
            .padding(start = HbTheme.spacing.xs, end = HbTheme.spacing.xs, top = HbTheme.spacing.xs),
        gap = HbTheme.spacing.xxs,
    ) {
        BrowserTabMenu(state, onIntent)
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val dimensions = HbTheme.dimensions
            val labelWidth = dimensions.browserGroupLabelMaxWidth
            val available = maxWidth - (labelWidth + HbTheme.spacing.m) * groups.size
            val tabWidth = (available / state.tabs.size.coerceAtLeast(1)).coerceIn(
                dimensions.browserTabMinWidth,
                dimensions.browserTabMaxWidth,
            )
            HbRow(
                Modifier.selectableGroup().hbHorizontalScroll(rememberScrollState()),
                gap = HbTheme.spacing.m,
            ) {
                groups.entries.forEachIndexed { index, (groupId, tabs) ->
                    key(groupId) {
                        BrowserTabGroup(groupId, index, tabs, state.selectedTabId, labelWidth, tabWidth, onIntent)
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

@Composable
private fun BrowserTabGroup(
    groupId: String?,
    index: Int,
    tabs: List<BrowserTabUi>,
    selectedTabId: String?,
    labelWidth: Dp,
    tabWidth: Dp,
    onIntent: (BrowserScreenIntent) -> Unit,
) {
    val color = when {
        groupId == null -> HbTheme.colors.textSecondary
        index % 2 == 0 -> HbTheme.colors.dataViolet
        else -> HbTheme.colors.dataCyan
    }
    val stroke = HbTheme.dimensions.borderWidth
    HbRow(
        Modifier.testTag("browser-group-${groupId ?: "personal"}").drawBehind {
            val y = size.height - stroke.toPx() / 2
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke.toPx())
        },
        gap = HbTheme.spacing.xxs,
    ) {
        HbText(
            stringResource(
                if (groupId == null) Res.string.browser_user_group else Res.string.browser_session_group,
                index + 1,
            ),
            Modifier.widthIn(max = labelWidth)
                .background(color, HbTheme.shapes.small)
                .padding(horizontal = HbTheme.spacing.xs, vertical = HbTheme.spacing.xxs),
            style = HbTheme.typography.caption,
            color = accessibleContentColor(color),
            maxLines = 1,
        )
        tabs.forEach { tab ->
            key(tab.id) { BrowserTab(tab, selectedTabId == tab.id, tabWidth, onIntent) }
        }
    }
}

@Composable
private fun BrowserTab(tab: BrowserTabUi, isSelected: Boolean, width: Dp, onIntent: (BrowserScreenIntent) -> Unit) {
    val title = tab.title.ifBlank { stringResource(Res.string.browser_new_tab) }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected, width) {
        if (isSelected) bringIntoView.bringIntoView()
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

@Composable
private fun BrowserTabMenu(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }
    val newTabTitle = stringResource(Res.string.browser_new_tab)
    HbMenuButton(
        icon = HbIcons.ChevronDown,
        contentDescription = stringResource(Res.string.browser_all_tabs),
        items = state.tabs.map { tab ->
            HbMenuItem(
                tab.id,
                tab.title.ifBlank { newTabTitle },
                HbIcons.Globe,
                isChecked = tab.id == state.selectedTabId,
            )
        }.toImmutableList(),
        isExpanded = isExpanded,
        onExpandedChange = { isExpanded = it },
        onItem = { onIntent(BrowserScreenIntent.SelectTab(it)) },
        modifier = Modifier.testTag("browser-all-tabs"),
    )
}
