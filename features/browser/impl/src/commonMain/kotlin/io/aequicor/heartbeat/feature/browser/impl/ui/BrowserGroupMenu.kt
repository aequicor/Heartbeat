package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_all_tabs
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_group_selector
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_new_tab
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_session_group
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_tab_groups
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_user_group
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Group selection returns to its last visited live tab; the tab inventory also crosses group boundaries. */
@Composable
internal fun BrowserGroupMenu(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val groups = state.tabs.groupBy { it.groupId }.entries.toList()
    val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId } ?: state.tabs.firstOrNull()
    val selectedGroup = groups.firstOrNull { it.key == selectedTab?.groupId }
    // This is navigation history local to the mounted screen, not a second source of selected state.
    val lastVisited = remember { mutableMapOf<String?, String>() }
    SideEffect {
        lastVisited.entries.removeAll { (group, tab) -> state.tabs.none { it.id == tab && it.groupId == group } }
        if (selectedTab != null) lastVisited[selectedTab.groupId] = selectedTab.id
    }
    val personal = stringResource(Res.string.browser_user_group)
    val labels = groups.mapIndexed { index, group ->
        if (group.key == null) personal else stringResource(Res.string.browser_session_group, index + 1)
    }
    val groupIndex = groups.indexOf(selectedGroup)
    val groupLabel = labels.getOrNull(groupIndex) ?: personal
    val count = selectedGroup?.value?.size ?: 0
    val allTabs = stringResource(Res.string.browser_all_tabs)
    val groupHeading = stringResource(Res.string.browser_tab_groups)
    val newTab = stringResource(Res.string.browser_new_tab)
    val items = buildList {
        add(HbMenuItem("groups", groupHeading, isEnabled = false))
        groups.forEachIndexed { index, group ->
            add(
                HbMenuItem(
                    "group:$index",
                    labels[index],
                    isChecked = group.key == selectedTab?.groupId,
                    badge = group.value.size.toString(),
                ),
            )
        }
        add(
            HbMenuItem(
                "inventory",
                allTabs,
                isEnabled = false,
                isGroupStart = true,
                badge = state.tabs.size.toString(),
            ),
        )
        state.tabs.forEach { tab ->
            add(
                HbMenuItem(
                    "tab:${tab.id}",
                    tab.title.ifBlank { newTab },
                    HbIcons.Globe,
                    isChecked = tab.id == selectedTab?.id,
                ),
            )
        }
    }.toImmutableList()
    HbMenuButton(
        icon = if (isExpanded) HbIcons.ChevronUp else HbIcons.ChevronDown,
        contentDescription = stringResource(Res.string.browser_group_selector, groupLabel, count),
        items = items,
        isExpanded = isExpanded,
        onExpandedChange = { isExpanded = it },
        onItem = { id ->
            val tab = if (id.startsWith("group:")) {
                groups.getOrNull(id.removePrefix("group:").toIntOrNull() ?: -1)?.let { group ->
                    group.value.firstOrNull { it.id == lastVisited[group.key] } ?: group.value.firstOrNull()
                }
            } else {
                state.tabs.firstOrNull { "tab:${it.id}" == id }
            }
            if (tab != null) onIntent(BrowserScreenIntent.SelectTab(tab.id))
        },
        modifier = modifier.testTag("browser-all-tabs"),
        label = groupLabel,
        badge = count.toString(),
    )
}
