package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.hbAttachmentInput
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPhase
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_chat
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_disabled
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_dismiss
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_error
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_loading
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_questions
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_retry
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_sessions
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_sources
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_toggle_panel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Research layout of the studio chat area: the conversation in the chat column and, on wide windows, a collapsible
 * side panel with sessions, questions and sources. The studio owns the header and sidebar around it; [onClose]
 * switches the chat area back to the regular chat.
 *
 * Drops and clipboard screenshots are captured on this screen root rather than on the composer, so the gesture
 * reaches the conversation with keyboard focus anywhere inside it; while this area is attached, the studio keeps
 * its own capture off.
 */
@Composable
internal fun ResearchScreenContent(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capture = researchAttachmentCapture(state, onIntent)
    Box(modifier.fillMaxSize().testTag("research-screen").then(capture).background(HbTheme.colors.background)) {
        HbColumn(Modifier.fillMaxSize().imePadding(), gap = HbTheme.spacing.none) {
            if (state.hasError && state.phase == ResearchPhase.Ready) ResearchError(onIntent)
            when (state.phase) {
                ResearchPhase.Ready -> ResearchWorkspace(state, onIntent, onClose, Modifier.weight(1f))

                ResearchPhase.Loading, ResearchPhase.Disabled, ResearchPhase.Error ->
                    ResearchStatus(state.phase, onIntent, Modifier.weight(1f))
            }
        }
        if (state.isResourceDialogOpen) ResearchResourceDialog(state, onIntent)
    }
}

@Composable
private fun ResearchWorkspace(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPanelOpen by rememberSaveable { mutableStateOf(true) }
    var panelTab by rememberSaveable { mutableStateOf(ResearchPane.Questions) }
    HbBoxWithConstraints(modifier.fillMaxWidth()) {
        val dimensions = HbTheme.dimensions
        if (maxWidth >= dimensions.paneMinWidth + dimensions.inspectorPanelWidth) {
            HbRow(Modifier.fillMaxSize(), gap = HbTheme.spacing.none, verticalAlignment = Alignment.Top) {
                ResearchConversation(
                    state = state,
                    onIntent = onIntent,
                    onClose = onClose,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    panelToggle = { ResearchPanelToggle(isPanelOpen) { isPanelOpen = !isPanelOpen } },
                )
                if (isPanelOpen) {
                    ResearchSidePanel(
                        state = state,
                        onIntent = onIntent,
                        selected = panelTab,
                        onSelect = { panelTab = it },
                        onHide = { isPanelOpen = false },
                        modifier = Modifier.width(dimensions.inspectorPanelWidth).fillMaxHeight(),
                    )
                }
            }
        } else {
            CompactResearchWorkspace(state, onIntent, onClose)
        }
    }
}

@Composable
private fun ResearchPanelToggle(isOpen: Boolean, onToggle: () -> Unit) {
    HbIconButton(
        icon = HbIcons.Sidebar,
        contentDescription = stringResource(Res.string.research_toggle_panel),
        onClick = onToggle,
        modifier = Modifier.testTag("research-toggle-panel"),
        isSelected = isOpen,
        size = HbTheme.dimensions.composerActionSize,
    )
}

/** Sessions, questions and sources next to the conversation, like an inspector; tabs keep it one column wide. */
@Composable
private fun ResearchSidePanel(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    selected: ResearchPane,
    onSelect: (ResearchPane) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier.testTag("research-side-panel"), gap = HbTheme.spacing.none) {
        HbDivider(Modifier.fillMaxHeight().width(HbTheme.dimensions.borderWidth))
        HbColumn(
            Modifier.weight(1f).fillMaxHeight().background(HbTheme.surfaces.sidebar),
            gap = HbTheme.spacing.none,
        ) {
            HbRow(
                Modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.headerHeight)
                    .padding(horizontal = HbTheme.spacing.s),
                gap = HbTheme.spacing.xxs,
            ) {
                PaneTabs(ResearchPane.panel, selected, onSelect, Modifier.weight(1f))
                HbIconButton(
                    icon = HbIcons.Close,
                    contentDescription = stringResource(Res.string.research_toggle_panel),
                    onClick = onHide,
                    modifier = Modifier.testTag("research-hide-panel"),
                    size = HbTheme.dimensions.navigationRowHeight,
                )
            }
            val content = Modifier.weight(1f).fillMaxWidth().padding(horizontal = HbTheme.spacing.xs)
            when (selected) {
                ResearchPane.Sessions -> ResearchSessions(state, onIntent, content)
                ResearchPane.Questions, ResearchPane.Chat -> ResearchQuestions(state, onIntent, content)
                ResearchPane.Sources -> ResearchSources(state, onIntent, content)
            }
        }
    }
}

@Composable
private fun PaneTabs(
    panes: List<ResearchPane>,
    selected: ResearchPane,
    onSelect: (ResearchPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier.selectableGroup(), gap = HbTheme.spacing.xxs) {
        panes.forEach { pane ->
            HbNavigationItem(
                label = stringResource(pane.label),
                onClick = { onSelect(pane) },
                modifier = Modifier.weight(1f).testTag("research-tab-${pane.name}"),
                isSelected = pane == selected,
                role = Role.Tab,
                minHeight = HbTheme.dimensions.navigationRowHeight,
            )
        }
    }
}

@Composable
private fun CompactResearchWorkspace(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    onClose: () -> Unit,
) {
    var selectedPane by rememberSaveable { mutableStateOf(ResearchPane.Chat) }
    val onNavigate: (ResearchScreenIntent) -> Unit = { intent ->
        onIntent(intent)
        if (intent is ResearchScreenIntent.SelectQuestion || intent == ResearchScreenIntent.NewQuestion) {
            selectedPane = ResearchPane.Chat
        } else if (intent is ResearchScreenIntent.SelectSession || intent == ResearchScreenIntent.NewSession) {
            selectedPane = ResearchPane.Questions
        }
    }
    HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
        PaneTabs(
            ResearchPane.entries,
            selectedPane,
            { selectedPane = it },
            Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.s, vertical = HbTheme.spacing.xs),
        )
        val contentModifier = Modifier.weight(1f).fillMaxWidth()
        when (selectedPane) {
            ResearchPane.Sessions -> ResearchSessions(state, onNavigate, contentModifier.padding(HbTheme.spacing.xs))
            ResearchPane.Questions -> ResearchQuestions(state, onNavigate, contentModifier.padding(HbTheme.spacing.xs))
            ResearchPane.Chat -> ResearchConversation(state, onNavigate, onClose, contentModifier)
            ResearchPane.Sources -> ResearchSources(state, onNavigate, contentModifier.padding(HbTheme.spacing.xs))
        }
    }
}

@Composable
private fun ResearchError(onIntent: (ResearchScreenIntent) -> Unit, modifier: Modifier = Modifier) {
    HbRow(
        modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.l)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("research-error"),
    ) {
        HbText(stringResource(Res.string.research_error), Modifier.weight(1f), color = HbTheme.colors.error)
        HbIconButton(
            icon = HbIcons.Close,
            contentDescription = stringResource(Res.string.research_dismiss),
            onClick = { onIntent(ResearchScreenIntent.DismissError) },
            modifier = Modifier.testTag("research-dismiss-error"),
        )
    }
}

@Composable
private fun ResearchStatus(
    phase: ResearchPhase,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().hbVerticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        HbColumn(Modifier.padding(HbTheme.spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
            if (phase == ResearchPhase.Loading) HbActivityIndicator()
            HbText(
                stringResource(
                    when (phase) {
                        ResearchPhase.Loading -> Res.string.research_loading
                        ResearchPhase.Disabled -> Res.string.research_disabled
                        ResearchPhase.Ready, ResearchPhase.Error -> Res.string.research_error
                    },
                ),
            )
            if (phase != ResearchPhase.Loading) {
                HbButton(
                    stringResource(Res.string.research_retry),
                    { onIntent(ResearchScreenIntent.Retry) },
                    Modifier.testTag("research-retry"),
                    style = HbButtonStyle.Secondary,
                )
            }
        }
    }
}

/** Native capture of the whole conversation; its placement is explained by [ResearchScreenContent]. */
@Composable
private fun researchAttachmentCapture(state: ResearchScreenState, onIntent: (ResearchScreenIntent) -> Unit): Modifier =
    hbAttachmentInput(
        enabled = state.isEditable && state.isFileImportAvailable,
        onFiles = { onIntent(ResearchScreenIntent.DroppedFiles(it)) },
        onImage = { onIntent(ResearchScreenIntent.PastedImage(it)) },
    )

private enum class ResearchPane(val label: StringResource) {
    Sessions(Res.string.research_sessions),
    Questions(Res.string.research_questions),
    Chat(Res.string.research_chat),
    Sources(Res.string.research_sources),
    ;

    companion object {
        /** Tabs of the wide side panel; the conversation itself stays in the chat column. */
        val panel: List<ResearchPane> = listOf(Questions, Sources, Sessions)
    }
}
