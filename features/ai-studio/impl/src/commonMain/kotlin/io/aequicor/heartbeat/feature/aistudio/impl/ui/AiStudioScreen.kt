package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.components.hbAttachmentInput
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.NativeAttachmentUi
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
internal fun AiStudioScreen(
    model: AiStudioModel,
    exits: StudioExits,
    modifier: Modifier = Modifier,
    chatArea: ComposableComponent? = null,
) {
    val state by produceState(AiStudioScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    AiStudioContent(state, model.store::intent, exits, modifier, chatArea)
}

@Composable
internal fun AiStudioContent(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    modifier: Modifier = Modifier,
    chatArea: ComposableComponent? = null,
) {
    Box(modifier.fillMaxSize().testTag("ai-studio").background(HbTheme.colors.background)) {
        Box(Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop)) {
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

                    StudioPhase.Ready -> StudioWorkspace(state, onIntent, exits, chatArea)
                }
                if (HbTheme.dimensions.isDesktop && state.phase != StudioPhase.Ready) {
                    HbWindowDragArea(Modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight)) { }
                }
            }
        }
    }
}

@Composable
private fun StudioWorkspace(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    chatArea: ComposableComponent?,
) {
    val focus = remember { StudioFocusState() }
    val dispatch: (AiStudioScreenIntent) -> Unit = { intent ->
        focus.beforeIntent(intent, state)
        onIntent(intent)
    }
    HbBoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val dimensions = HbTheme.dimensions
        val studio = HbTheme.dimensions
        val sidebarWidth by animateDpAsState(
            if (state.sidebar.isVisible) studio.sidebarWidth + studio.panelGap else HbTheme.spacing.none,
            animationSpec = if (HbTheme.motion.isReducedMotion) snap() else tween(HbTheme.motion.fastMillis),
            label = "studioSidebarWidth",
        )
        val minimumWideWidth = studio.sidebarWidth +
            studio.outerInset * 2 + studio.panelGap + dimensions.paneMinWidth + studio.messagePadding * 2
        val isCompact = availableWidth < maxOf(dimensions.compactBreakpoint, minimumWideWidth)
        Box(
            Modifier.fillMaxSize().studioShortcuts(state, isCompact, focus, dispatch)
                .then(workspaceAttachmentCapture(state, isChatAreaAttached = chatArea != null, onIntent = dispatch))
                .testTag("studio-workspace"),
        ) {
            if (isCompact) {
                CompactWorkspace(
                    state,
                    dispatch,
                    exits,
                    focus,
                    drawerWidth = minOf(dimensions.drawerMaxWidth, availableWidth),
                    chatArea = chatArea,
                )
            } else {
                val panesWidth = availableWidth - sidebarWidth - studio.outerInset * 2
                WideWorkspace(
                    state,
                    dispatch,
                    exits,
                    focus,
                    sidebarWidth,
                    isSplitAllowed = panesWidth >= dimensions.paneMinWidth * 2 + studio.panelGap,
                    chatArea = chatArea,
                )
            }
        }
    }
}

@Composable
private fun WideWorkspace(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    focus: StudioFocusState,
    sidebarWidth: Dp,
    isSplitAllowed: Boolean,
    chatArea: ComposableComponent?,
) {
    HbRow(
        Modifier.fillMaxSize().padding(
            horizontal = HbTheme.dimensions.outerInset,
            vertical = HbTheme.dimensions.verticalInset,
        ),
        gap = HbTheme.spacing.none,
        verticalAlignment = Alignment.Top,
    ) {
        if (sidebarWidth > HbTheme.spacing.none) {
            Box(Modifier.width(sidebarWidth).fillMaxHeight().clipToBounds()) {
                StudioSidebar(
                    input = state.sidebarInput(),
                    onIntent = onIntent,
                    isOpenBesideAllowed = isSplitAllowed,
                    exits = exits,
                    focus = focus,
                    modifier = Modifier.width(HbTheme.dimensions.sidebarWidth).fillMaxHeight()
                        .background(HbTheme.surfaces.sidebar).studioSidebarFocus(focus),
                )
            }
        }
        if (chatArea != null) {
            StudioChatArea(
                chatArea,
                onToggleSidebar = { onIntent(AiStudioScreenIntent.ToggleSidebar) },
                isAtWindowLeadingEdge = !state.sidebar.isVisible,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            return@HbRow
        }
        val shown = if (isSplitAllowed) state.panes else state.panes.filter { it.id == state.focusedPaneId }
        HbRow(
            Modifier.weight(1f).fillMaxHeight(),
            gap = HbTheme.dimensions.panelGap,
            verticalAlignment = Alignment.Top,
        ) {
            shown.forEach { pane ->
                key(pane.id) {
                    PaneFrame(pane != shown.first(), Modifier.weight(1f).fillMaxHeight()) {
                        StudioPaneView(
                            content = state.paneContent(pane),
                            onOpenResearch = exits.onOpenResearch,
                            questions = exits.questions,
                            onIntent = onIntent,
                            layout = PaneLayout(
                                isSplitAllowed = isSplitAllowed && state.panes.size == 1,
                                isCloseAllowed = shown.size > 1,
                                isCompact = false,
                            ),
                            isAtWindowLeadingEdge = !state.sidebar.isVisible && pane == shown.first(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** The divider overlays the pane edge, preserving the existing split threshold and mobile spacing. */
@Composable
private fun PaneFrame(hasDivider: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        if (hasDivider && HbTheme.dimensions.isDesktop) HbDivider(isVertical = true)
    }
}

@Composable
private fun CompactWorkspace(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    exits: StudioExits,
    focus: StudioFocusState,
    drawerWidth: Dp,
    chatArea: ComposableComponent?,
) {
    Box(Modifier.fillMaxSize()) {
        if (chatArea != null) {
            StudioChatArea(
                chatArea,
                onToggleSidebar = { onIntent(AiStudioScreenIntent.SetDrawerOpen(true)) },
                isAtWindowLeadingEdge = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        state.panes.takeIf { chatArea == null }?.firstOrNull { it.id == state.focusedPaneId }?.let { pane ->
            StudioPaneView(
                content = state.paneContent(pane),
                onOpenResearch = exits.onOpenResearch,
                questions = exits.questions,
                onIntent = onIntent,
                layout = PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = true),
                isAtWindowLeadingEdge = true,
                modifier = Modifier.fillMaxSize(),
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
                background = HbTheme.colors.assistantSurface,
                shape = HbTheme.shapes.large,
            ) {
                StudioSidebar(
                    state.sidebarInput(),
                    onIntent,
                    isOpenBesideAllowed = false,
                    exits = exits,
                    focus = focus,
                    modifier = Modifier.fillMaxSize().studioSidebarFocus(focus),
                    isDrawer = true,
                )
            }
        }
    }
}

@Composable
private fun StudioLoading(modifier: Modifier = Modifier) {
    HbColumn(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HbActivityIndicator(size = HbTheme.dimensions.iconLargeSize)
        HbText(stringResource(Res.string.studio_loading), color = HbTheme.colors.textPrimary)
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
        HbColumn(
            Modifier.widthIn(max = HbTheme.dimensions.messageMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HbText(stringResource(Res.string.studio_title), style = HbTheme.typography.display)
            HbEmptyState(
                stringResource(Res.string.studio_empty),
                description = stringResource(Res.string.studio_description),
                action = {
                    HbButton(
                        stringResource(Res.string.studio_back),
                        onBack,
                        Modifier.testTag("studio-back"),
                        style = HbButtonStyle.Secondary,
                    )
                },
            )
        }
    }
}

/**
 * Drops and clipboard screenshots of the whole workspace, aimed at its focused pane. The capture belongs to the
 * screen root instead of a composer: keyboard focus rests on this container after startup and follows a click into
 * the transcript, so a shortcut bound to the composer never observes those pastes. A nested chat area owns the same
 * gestures inside its own screen, which is why the studio keeps its hands off while one is attached.
 */
@Composable
private fun workspaceAttachmentCapture(
    state: AiStudioScreenState,
    isChatAreaAttached: Boolean,
    onIntent: (AiStudioScreenIntent) -> Unit,
): Modifier {
    val paneId = state.focusedPaneId
    return hbAttachmentInput(
        enabled = !isChatAreaAttached && state.canCaptureAttachments(paneId),
        onFiles = { paths ->
            onIntent(AiStudioScreenIntent.ImportAttachments(paneId, paths.map(NativeAttachmentUi::File)))
        },
        onImage = { bytes ->
            onIntent(AiStudioScreenIntent.ImportAttachments(paneId, listOf(NativeAttachmentUi.Image(bytes))))
        },
    )
}

/**
 * A feature shown in the chat area instead of the studio panes, e.g. research. The studio keeps its sidebar and
 * draws the area header itself: the sidebar toggle and the macOS traffic-light inset belong to the studio window.
 */
@Composable
private fun StudioChatArea(
    component: ComposableComponent,
    onToggleSidebar: () -> Unit,
    isAtWindowLeadingEdge: Boolean,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.testTag("studio-chat-area"), gap = HbTheme.spacing.none) {
        ChatAreaHeader(onToggleSidebar, isAtWindowLeadingEdge)
        Box(Modifier.weight(1f).fillMaxWidth()) { component.Content(Modifier.fillMaxSize()) }
    }
}
