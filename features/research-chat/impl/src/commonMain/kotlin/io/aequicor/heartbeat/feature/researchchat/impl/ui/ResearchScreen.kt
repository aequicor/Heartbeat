package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.aequicor.heartbeat.ds.components.HbGlassScene
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbStudioBackdrop
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbStudioTheme
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPhase
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_back
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_chat
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_disabled
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_dismiss
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_error
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_loading
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_questions
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_retry
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_sessions
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_sources
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Research workspace with independent questions and an explicitly selected source set. */
@Composable
internal fun ResearchScreenContent(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbStudioTheme {
        HbGlassScene(modifier.fillMaxSize().testTag("research-screen")) {
            HbStudioBackdrop(Modifier.fillMaxSize(), isAmbient = true) {
                HbColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), gap = HbTheme.spacing.none) {
                    ResearchHeader(onBack)
                    if (state.hasError && state.phase == ResearchPhase.Ready) ResearchError(onIntent)
                    when (state.phase) {
                        ResearchPhase.Ready -> ResearchWorkspace(state, onIntent, Modifier.weight(1f))

                        ResearchPhase.Loading, ResearchPhase.Disabled, ResearchPhase.Error ->
                            ResearchStatus(state.phase, onIntent, Modifier.weight(1f))
                    }
                }
            }
            if (state.isResourceDialogOpen) ResearchResourceDialog(state, onIntent)
        }
    }
}

@Composable
private fun ResearchHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    HbRow(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
        HbIconButton(
            icon = HbIcons.ArrowLeft,
            contentDescription = stringResource(Res.string.research_back),
            onClick = onBack,
            modifier = Modifier.testTag("research-back"),
        )
        HbText(stringResource(Res.string.research_title), style = HbTheme.typography.title)
    }
}

@Composable
private fun ResearchWorkspace(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbBoxWithConstraints(modifier.fillMaxWidth().padding(HbTheme.spacing.m)) {
        if (maxWidth >= HbTheme.dimensions.expandedBreakpoint) {
            HbRow(Modifier.fillMaxSize(), gap = HbTheme.spacing.m, verticalAlignment = Alignment.Top) {
                ResearchSessions(state, onIntent, Modifier.width(HbTheme.dimensions.sidebarWidth).fillMaxHeight())
                ResearchQuestions(state, onIntent, Modifier.width(HbTheme.dimensions.sidebarWidth).fillMaxHeight())
                ResearchConversation(state, onIntent, Modifier.weight(1f).fillMaxHeight())
                ResearchSources(
                    state,
                    onIntent,
                    Modifier.width(HbTheme.dimensions.navigationPanelWidth).fillMaxHeight(),
                )
            }
        } else {
            CompactResearchWorkspace(state, onIntent)
        }
    }
}

@Composable
private fun CompactResearchWorkspace(state: ResearchScreenState, onIntent: (ResearchScreenIntent) -> Unit) {
    var selectedPane by remember { mutableStateOf(ResearchPane.Chat) }
    val onNavigate: (ResearchScreenIntent) -> Unit = { intent ->
        onIntent(intent)
        if (intent is ResearchScreenIntent.SelectQuestion || intent == ResearchScreenIntent.NewQuestion) {
            selectedPane = ResearchPane.Chat
        } else if (intent is ResearchScreenIntent.SelectSession || intent == ResearchScreenIntent.NewSession) {
            selectedPane = ResearchPane.Questions
        }
    }
    HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.m) {
        HbRow(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.xxs) {
            ResearchPane.entries.forEach { pane ->
                HbNavigationItem(
                    label = stringResource(pane.label),
                    onClick = { selectedPane = pane },
                    modifier = Modifier.weight(1f).testTag("research-tab-${pane.name}"),
                    isSelected = pane == selectedPane,
                    role = Role.Tab,
                )
            }
        }
        val contentModifier = Modifier.weight(1f).fillMaxWidth()
        when (selectedPane) {
            ResearchPane.Sessions -> ResearchSessions(state, onNavigate, contentModifier)
            ResearchPane.Questions -> ResearchQuestions(state, onNavigate, contentModifier)
            ResearchPane.Chat -> ResearchConversation(state, onNavigate, contentModifier)
            ResearchPane.Sources -> ResearchSources(state, onNavigate, contentModifier)
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

private enum class ResearchPane(val label: StringResource) {
    Sessions(Res.string.research_sessions),
    Questions(Res.string.research_questions),
    Chat(Res.string.research_chat),
    Sources(Res.string.research_sources),
}
