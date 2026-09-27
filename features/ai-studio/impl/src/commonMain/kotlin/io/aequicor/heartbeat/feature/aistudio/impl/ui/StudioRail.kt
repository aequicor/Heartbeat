package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_archive
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_search
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_sessions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_sidebar_hide
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_sidebar_show
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_toggles
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_back
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_connections
import org.jetbrains.compose.resources.stringResource

/**
 * Studio destinations: sessions, search, archive, feature toggles and, when enabled, engine connections,
 * plus leaving the studio.
 * A vertical rail beside the sidebar on wide windows; a row at the top of the drawer on compact ones.
 */
@Composable
internal fun StudioRail(
    sidebar: SidebarUi,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    modifier: Modifier = Modifier,
    isHorizontal: Boolean = false,
) {
    val destinations: @Composable () -> Unit = {
        HbIconButton(
            icon = if (sidebar.mode == SidebarMode.Workspace) HbIcons.HomeFilled else HbIcons.Home,
            contentDescription = stringResource(Res.string.rail_sessions),
            onClick = { onIntent(AiStudioScreenIntent.ShowSidebarMode(SidebarMode.Workspace)) },
            modifier = Modifier.testTag("rail-sessions"),
            isSelected = sidebar.mode == SidebarMode.Workspace && sidebar.isVisible,
        )
        HbIconButton(
            icon = HbIcons.Search,
            contentDescription = stringResource(Res.string.rail_search),
            onClick = { onIntent(AiStudioScreenIntent.ToggleSearch) },
            modifier = Modifier.testTag("rail-search"),
            isSelected = sidebar.isSearchVisible,
        )
        HbIconButton(
            icon = HbIcons.Archive,
            contentDescription = stringResource(Res.string.rail_archive),
            onClick = { onIntent(AiStudioScreenIntent.ShowSidebarMode(SidebarMode.Archive)) },
            modifier = Modifier.testTag("rail-archive"),
            isSelected = sidebar.mode == SidebarMode.Archive && sidebar.isVisible,
        )
        HbIconButton(
            icon = HbIcons.Sliders,
            contentDescription = stringResource(Res.string.rail_toggles),
            onClick = exits.onOpenToggles,
            modifier = Modifier.testTag("rail-toggles"),
        )
        exits.onOpenConnections?.let { openConnections ->
            HbIconButton(
                icon = HbIcons.Link,
                contentDescription = stringResource(Res.string.studio_connections),
                onClick = openConnections,
                modifier = Modifier.testTag("rail-connections"),
            )
        }
    }
    val exit: @Composable () -> Unit = {
        HbIconButton(
            icon = HbIcons.ArrowLeft,
            contentDescription = stringResource(Res.string.studio_back),
            onClick = exits.onBack,
            modifier = Modifier.testTag("rail-back"),
        )
    }
    if (isHorizontal) {
        HbRow(modifier.fillMaxWidth().padding(HbTheme.spacing.s), gap = HbTheme.spacing.xs) {
            destinations()
            Spacer(Modifier.weight(1f))
            exit()
        }
    } else {
        HbColumn(
            modifier.width(HbTheme.dimensions.navigationRailWidth).padding(vertical = HbTheme.spacing.m)
                .testTag("studio-rail"),
            gap = HbTheme.spacing.xs,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            destinations()
            Spacer(Modifier.weight(1f))
            HbIconButton(
                icon = HbIcons.Sidebar,
                contentDescription = stringResource(
                    if (sidebar.isVisible) Res.string.rail_sidebar_hide else Res.string.rail_sidebar_show,
                ),
                onClick = { onIntent(AiStudioScreenIntent.ToggleSidebar) },
                modifier = Modifier.testTag("rail-sidebar"),
            )
            exit()
        }
    }
}
