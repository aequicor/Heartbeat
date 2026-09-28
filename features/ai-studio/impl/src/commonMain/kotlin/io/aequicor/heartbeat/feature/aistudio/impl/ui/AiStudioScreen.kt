package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_back
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_description
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_empty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_error
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_loading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_retry
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun AiStudioScreen(model: AiStudioModel, exits: StudioExits, modifier: Modifier = Modifier) {
    val state by produceState(AiStudioScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    AiStudioContent(state, model.store::intent, exits, modifier)
}

@Composable
internal fun AiStudioContent(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    modifier: Modifier = Modifier,
) {
    HbGlassScene(modifier.fillMaxSize().testTag("ai-studio")) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (state.phase) {
                StudioPhase.Loading -> StudioLoading(Modifier.align(Alignment.Center))

                StudioPhase.Error -> StudioMessage(
                    title = stringResource(Res.string.studio_error),
                    action = stringResource(Res.string.studio_retry),
                    onAction = { onIntent(AiStudioScreenIntent.Retry) },
                    modifier = Modifier.align(Alignment.Center),
                )

                StudioPhase.Disabled -> StudioPlaceholder(exits.onBack)

                StudioPhase.Ready -> StudioWorkspace(state, onIntent, exits)
            }
        }
    }
}

@Composable
private fun StudioWorkspace(state: AiStudioScreenState, onIntent: (AiStudioScreenIntent) -> Unit, exits: StudioExits) {
    HbBoxWithConstraints(Modifier.fillMaxSize().testTag("studio-workspace")) {
        val dimensions = HbTheme.dimensions
        if (maxWidth < dimensions.compactBreakpoint) {
            CompactWorkspace(state, onIntent, exits, drawerWidth = minOf(dimensions.drawerMaxWidth, maxWidth))
        } else {
            val panesWidth = maxWidth - dimensions.navigationRailWidth -
                if (state.sidebar.isVisible) dimensions.navigationPanelWidth else HbTheme.spacing.none
            WideWorkspace(state, onIntent, exits, isSplitAllowed = panesWidth >= dimensions.paneMinWidth * 2)
        }
    }
}

@Composable
private fun WideWorkspace(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    isSplitAllowed: Boolean,
) {
    HbRow(Modifier.fillMaxSize(), gap = HbTheme.spacing.none, verticalAlignment = Alignment.Top) {
        StudioRail(state.sidebar, onIntent, exits, Modifier.fillMaxHeight())
        if (state.sidebar.isVisible) {
            StudioSidebar(
                input = state.sidebarInput(),
                onIntent = onIntent,
                isOpenBesideAllowed = isSplitAllowed,
                modifier = Modifier.width(HbTheme.dimensions.navigationPanelWidth).fillMaxHeight(),
            )
        }
        val shown = if (isSplitAllowed) state.panes else state.panes.filter { it.id == state.focusedPaneId }
        HbRow(
            Modifier.weight(1f).fillMaxHeight().padding(HbTheme.spacing.m),
            gap = HbTheme.spacing.m,
            verticalAlignment = Alignment.Top,
        ) {
            shown.forEach { pane ->
                key(pane.id) {
                    StudioPaneView(
                        content = state.paneContent(pane),
                        onOpenResearch = exits.onOpenResearch,
                        onIntent = onIntent,
                        layout = PaneLayout(
                            isSplitAllowed = isSplitAllowed && state.panes.size == 1,
                            isCloseAllowed = shown.size > 1,
                            isCompact = false,
                        ),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactWorkspace(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    drawerWidth: Dp,
) {
    Box(Modifier.fillMaxSize()) {
        state.panes.firstOrNull { it.id == state.focusedPaneId }?.let { pane ->
            StudioPaneView(
                content = state.paneContent(pane),
                onOpenResearch = exits.onOpenResearch,
                onIntent = onIntent,
                layout = PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = true),
                modifier = Modifier.fillMaxSize().padding(HbTheme.spacing.xs),
            )
        }
        if (state.sidebar.isDrawerOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(HbTheme.colors.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        onIntent(AiStudioScreenIntent.SetDrawerOpen(false))
                    }
                    .testTag("studio-scrim"),
            )
            HbPanel(
                Modifier.width(drawerWidth).fillMaxHeight().testTag("studio-drawer"),
                background = HbTheme.colors.surface,
                shape = HbTheme.shapes.large,
            ) {
                HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
                    StudioRail(state.sidebar, onIntent, exits, isHorizontal = true)
                    StudioSidebar(
                        state.sidebarInput(),
                        onIntent,
                        isOpenBesideAllowed = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioLoading(modifier: Modifier = Modifier) {
    HbColumn(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HbActivityIndicator(size = HbTheme.dimensions.iconLargeSize)
        HbText(stringResource(Res.string.studio_loading), color = HbTheme.colors.textSecondary)
    }
}

@Composable
private fun StudioMessage(title: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    HbColumn(modifier.padding(HbTheme.spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        HbText(title, style = HbTheme.typography.title.copy(textAlign = TextAlign.Center))
        HbButton(action, onAction, Modifier.testTag("studio-retry"))
    }
}

@Composable
private fun StudioPlaceholder(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        HbPanel(Modifier.widthIn(max = HbTheme.dimensions.chatMessageMaxWidth)) {
            HbColumn(Modifier.padding(HbTheme.spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                HbText(stringResource(Res.string.studio_title), style = HbTheme.typography.display)
                HbText(stringResource(Res.string.studio_empty), style = HbTheme.typography.title)
                HbText(
                    stringResource(Res.string.studio_description),
                    color = HbTheme.colors.textSecondary,
                    style = HbTheme.typography.body.copy(textAlign = TextAlign.Center),
                )
                HbButton(
                    stringResource(Res.string.studio_back),
                    onBack,
                    Modifier.testTag("studio-back"),
                    style = HbButtonStyle.Secondary,
                )
            }
        }
    }
}
