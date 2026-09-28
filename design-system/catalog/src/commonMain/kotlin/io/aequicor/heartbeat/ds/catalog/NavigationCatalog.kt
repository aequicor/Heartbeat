package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbNavigationHeader
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf

private val log = Log.tag("DS/NavigationCatalog")

/** Sidebar rows, rail actions, context chips and an anchored menu, wired to local catalog state. */
@Composable
internal fun NavigationExample(modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(0) }
    var isExpanded by remember { mutableStateOf(true) }
    var isMenuOpen by remember { mutableStateOf(false) }
    HbCard(modifier = modifier.testTag("catalog-navigation")) {
        HbText(hbString(HbString.NavigationMenus), style = HbTheme.typography.title)
        HbFlowRow {
            HbIconButton(HbIcons.Home, hbString(HbString.Workspace), onClick = { selected = 0 }, isSelected = true)
            HbIconButton(HbIcons.Archive, hbString(HbString.Archive), onClick = { selected = 1 })
            HbIconButton(HbIcons.Edit, hbString(HbString.NewSession), onClick = { selected = 2 })
            HbChip(hbString(HbString.AppName), icon = HbIcons.Folder, onClick = { isExpanded = !isExpanded })
            HbChip(hbString(HbString.LocalDemo), icon = HbIcons.Laptop)
        }
        HbColumn(Modifier.widthIn(max = HbTheme.dimensions.sidebarWidth).fillMaxWidth(), gap = HbTheme.spacing.xxs) {
            HbNavigationHeader(
                title = hbString(HbString.Projects),
                isExpanded = isExpanded,
                onToggle = { isExpanded = !isExpanded },
            )
            if (isExpanded) {
                HbNavigationItem(hbString(HbString.AppName), onClick = { selected = 0 }, icon = HbIcons.Folder)
                HbNavigationItem(
                    label = hbString(HbString.SeedPrompt),
                    onClick = { selected = 1 },
                    isSelected = selected == 1,
                    level = 1,
                ) { isActive ->
                    if (isActive || isMenuOpen) {
                        HbMenuButton(
                            icon = HbIcons.More,
                            contentDescription = hbString(HbString.MoreActions),
                            items = persistentListOf(
                                HbMenuItem("rename", hbString(HbString.Rename), HbIcons.Edit),
                                HbMenuItem("pin", hbString(HbString.Pin), HbIcons.Pin),
                                HbMenuItem("archive", hbString(HbString.Archive), HbIcons.Archive, isGroupStart = true),
                            ),
                            isExpanded = isMenuOpen,
                            onExpandedChange = { isMenuOpen = it },
                            onItem = { log.i { "catalog menu item=$it" } },
                        )
                    } else {
                        HbActivityIndicator(contentDescription = hbString(HbString.Working))
                    }
                }
                HbNavigationItem(
                    label = hbString(HbString.ChatTitle),
                    onClick = { selected = 2 },
                    isSelected = selected == 2,
                    isEmphasized = true,
                    level = 1,
                )
            }
        }
    }
}
