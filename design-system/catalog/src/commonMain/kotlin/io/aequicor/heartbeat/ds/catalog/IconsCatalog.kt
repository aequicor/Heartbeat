package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

private val CatalogIcons = listOf(
    HbIcons.Home, HbIcons.HomeFilled, HbIcons.History, HbIcons.Library,
    HbIcons.Images, HbIcons.Chat, HbIcons.More, HbIcons.Sidebar,
    HbIcons.ArrowLeft, HbIcons.ArrowRight, HbIcons.ArrowUp, HbIcons.ArrowDown,
    HbIcons.Folder, HbIcons.Laptop, HbIcons.Branch, HbIcons.Plus,
    HbIcons.Plan, HbIcons.Sparkles, HbIcons.Copy, HbIcons.Check,
    HbIcons.Alert, HbIcons.Stop, HbIcons.ChevronRight, HbIcons.ChevronDown,
)

@Composable
internal fun IconsCatalog(modifier: Modifier = Modifier) {
    HbCard(modifier = modifier) {
        CatalogHeading(HbString.Icons, HbString.IconsDescription)
        HbRow {
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
            CatalogIcons.forEach { icon ->
                HbColumn(
                    modifier = Modifier.width(HbTheme.dimensions.sidebarWidth / 2)
                        .padding(vertical = HbTheme.spacing.s),
                    gap = HbTheme.spacing.s,
                ) {
                    HbIcon(icon, contentDescription = null)
                    // API identifiers document the asset, rather than user-facing action labels.
                    HbText(icon.name, style = HbTheme.typography.caption)
                }
            }
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
