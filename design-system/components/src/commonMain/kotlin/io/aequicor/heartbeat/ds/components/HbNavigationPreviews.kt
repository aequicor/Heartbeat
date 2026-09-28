package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

@Preview
@Composable
private fun LightNavigationPreview() {
    HbTheme(darkTheme = false) { NavigationPreviewContent() }
}

@Preview
@Composable
private fun DarkNavigationPreview() {
    HbTheme(darkTheme = true) { NavigationPreviewContent() }
}

@Composable
private fun NavigationPreviewContent() {
    Box(Modifier.background(HbTheme.colors.background)) {
        HbColumn(modifier = Modifier.padding(HbTheme.spacing.l).width(HbTheme.dimensions.sidebarWidth)) {
            HbRow(gap = HbTheme.spacing.xs) {
                HbRailItem(HbIcons.Chats, "Chats", onClick = {}, isSelected = true)
                HbIconButton(HbIcons.Home, "Home", onClick = {}, isSelected = true)
                HbIconButton(HbIcons.Archive, "Archive", onClick = {})
                HbIconButton(HbIcons.Sliders, "Settings", onClick = {}, enabled = false)
            }
            HbNavigationHeader("Projects", onToggle = {})
            HbNavigationItem("heartbeat", onClick = {}, icon = HbIcons.Folder)
            HbNavigationItem("Design the engine facade", onClick = {}, isSelected = true, level = 1) {
                HbActivityIndicator()
            }
            HbNavigationItem("Review the pull request", onClick = {}, isEmphasized = true, level = 1)
            HbNavigationItem(
                "Studio conversation",
                onClick = {},
                isSelected = true,
                supportingText = "A two-line conversation preview",
                leadingContent = {
                    HbIcon(HbIcons.Chat, contentDescription = null, tint = HbTheme.surfaces.onSelected)
                },
                selectedBackground = HbTheme.surfaces.selected,
                selectedForeground = HbTheme.surfaces.onSelected,
            )
            HbRow(gap = HbTheme.spacing.xs) {
                HbChip("heartbeat", icon = HbIcons.Folder, onClick = {})
                HbChip("main", icon = HbIcons.Branch)
            }
        }
    }
}
