package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_archive
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_profile_settings
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_sessions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_toggles
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_back
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_connections
import org.jetbrains.compose.resources.stringResource

/** Existing destinations grouped in the unified sidebar footer; each retains its original callback and label. */
@Composable
internal fun StudioRail(
    sidebar: SidebarUi,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    modifier: Modifier = Modifier,
) {
    HbRow(
        modifier.fillMaxWidth().hbHorizontalScroll(rememberScrollState())
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs).testTag("sidebar-footer"),
        gap = HbTheme.spacing.m,
    ) {
        HbRow(gap = HbTheme.spacing.xxs) {
            FooterAction(
                HbIcons.Chats,
                stringResource(Res.string.rail_sessions),
                { onIntent(AiStudioScreenIntent.ShowSidebarMode(SidebarMode.Workspace)) },
                "rail-sessions",
                isSelected = sidebar.mode == SidebarMode.Workspace,
            )
            FooterAction(
                HbIcons.Archive,
                stringResource(Res.string.rail_archive),
                { onIntent(AiStudioScreenIntent.ShowSidebarMode(SidebarMode.Archive)) },
                "rail-archive",
                isSelected = sidebar.mode == SidebarMode.Archive,
            )
        }
        HbRow(gap = HbTheme.spacing.xxs) {
            FooterAction(HbIcons.Sliders, stringResource(Res.string.rail_toggles), exits.onOpenToggles, "rail-toggles")
            exits.onOpenProfileSettings?.let {
                FooterAction(
                    HbIcons.Settings,
                    stringResource(Res.string.rail_profile_settings),
                    it,
                    "rail-profile-settings",
                )
            }
            exits.onOpenConnections?.let {
                FooterAction(HbIcons.Link, stringResource(Res.string.studio_connections), it, "rail-connections")
            }
        }
        FooterAction(HbIcons.ArrowLeft, stringResource(Res.string.studio_back), exits.onBack, "rail-back")
    }
}

@Composable
private fun FooterAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    HbIconButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        modifier = modifier.testTag(tag),
        isSelected = isSelected,
        size = HbTheme.dimensions.navigationRowHeight,
    )
}
