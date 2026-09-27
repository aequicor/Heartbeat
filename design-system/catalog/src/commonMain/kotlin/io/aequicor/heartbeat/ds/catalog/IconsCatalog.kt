package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/IconsCatalog")

@Composable
internal fun IconsCatalog(modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<HbString?>(null) }
    val groups = remember(query, category) {
        CatalogIconGroups.asSequence().filter { category == null || it.title == category }.map { group ->
            group to group.icons.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }.filter { it.second.isNotEmpty() }.toList()
    }
    HbLazyColumn(modifier = modifier.testTag("icons-catalog")) {
        item {
            HbColumn {
                CatalogHeading(HbString.Icons, HbString.IconsDescription)
                HbText(hbString(HbString.IconSizes), style = HbTheme.typography.caption)
            }
        }
        item {
            HbTextField(
                value = query,
                onValueChange = {
                    log.d { "icon search updated length=${it.length}" }
                    query = it
                },
                modifier = Modifier.fillMaxWidth().testTag("icon-search"),
                placeholder = hbString(HbString.SearchIcons),
            )
        }
        item {
            HbFlowRow {
                ChoiceButton(hbString(HbString.AllIcons), category == null, onClick = {
                    log.i { "icon category cleared" }
                    category = null
                })
                CatalogIconGroups.forEach { group ->
                    ChoiceButton(hbString(group.title), category == group.title, onClick = {
                        log.i { "icon category selected category=${group.title}" }
                        category = group.title
                    })
                }
            }
        }
        item {
            HbText(
                "${hbString(HbString.MatchingIcons)}: ${groups.sumOf { it.second.size }} / ${HbIcons.All.size}",
                style = HbTheme.typography.caption,
                modifier = Modifier.testTag("icon-count").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (groups.isEmpty()) {
            item { HbText(hbString(HbString.NoIconsFound)) }
        }
        groups.forEach { (group, icons) ->
            item(key = group.title) {
                HbCard(modifier = Modifier.fillMaxWidth()) {
                    HbText(hbString(group.title), style = HbTheme.typography.title)
                    HbFlowRow(gap = HbTheme.spacing.m) {
                        icons.forEach { icon -> IconTile(icon) }
                    }
                }
            }
        }
        item { IconContextExamples() }
    }
}

@Composable
private fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    HbColumn(
        modifier = modifier.width(HbTheme.dimensions.iconTileWidth)
            .border(HbTheme.dimensions.borderWidth, HbTheme.colors.outlineSubtle, HbTheme.shapes.medium)
            .padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.m,
    ) {
        HbRow(gap = HbTheme.spacing.s) {
            listOf(
                HbTheme.dimensions.iconSmallSize,
                HbTheme.dimensions.iconSize,
                HbTheme.dimensions.iconLargeSize,
            ).forEach { size ->
                HbIcon(icon, contentDescription = null, modifier = Modifier.size(size))
            }
        }
        SelectionContainer {
            // Asset API identifiers intentionally stay in English in both interface languages.
            HbText(icon.name, style = HbTheme.typography.caption)
        }
    }
}

@Composable
private fun IconContextExamples(modifier: Modifier = Modifier) {
    HbCard(modifier = modifier.fillMaxWidth()) {
        HbFlowRow {
            Box(
                modifier = Modifier.size(HbTheme.dimensions.controlHeight)
                    .background(HbTheme.colors.primaryContainer, HbTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                HbIcon(HbIcons.HomeFilled, contentDescription = null, tint = HbTheme.colors.textPrimary)
            }
            HbIcon(HbIcons.History, contentDescription = null)
            HbIcon(HbIcons.Library, contentDescription = null)
            HbIcon(HbIcons.Images, contentDescription = null)
            HbIcon(HbIcons.Chat, contentDescription = null)
            HbIcon(HbIcons.More, contentDescription = null)
        }
        HbFlowRow {
            IconMetadata(HbIcons.Folder, hbString(HbString.AppName))
            IconMetadata(HbIcons.Laptop, hbString(HbString.LocalDemo))
            IconMetadata(HbIcons.Branch, "master")
        }
    }
}

@Composable
private fun IconMetadata(icon: ImageVector, label: String) {
    HbRow(gap = HbTheme.spacing.xs) {
        HbIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(HbTheme.dimensions.iconSmallSize),
            tint = HbTheme.colors.textPrimary,
        )
        HbText(label, style = HbTheme.typography.caption)
    }
}

@Preview
@Composable
private fun IconsLightPreview() {
    HbTheme(darkTheme = false) { IconsCatalog() }
}

@Preview
@Composable
private fun IconsDarkPreview() {
    HbTheme(darkTheme = true) { IconsCatalog() }
}
