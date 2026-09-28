package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbChatTranscript
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.jump_latest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_heading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_heading_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_close
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_open_sidebar
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_split
import io.aequicor.heartbeat.feature.aistudio.impl.resources.research_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_actions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_read_only
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_new_session
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stop_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stop_unsupported
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stopping
import io.aequicor.heartbeat.feature.aistudio.impl.resources.streaming
import io.aequicor.heartbeat.feature.aistudio.impl.resources.submit_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.working_for
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * One workspace column: a session transcript or the new-session page, with its composer.
 * [content] holds only this pane's data, so streaming into another pane does not recompose it.
 */
@Composable
internal fun StudioPaneView(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    layout: PaneLayout,
    modifier: Modifier = Modifier,
    onOpenResearch: ((String) -> Unit)? = null,
) {
    val pane = content.pane
    HbPanel(
        modifier.focusOnPress(content.isFocused, pane.id) { onIntent(AiStudioScreenIntent.FocusPane(pane.id)) }
            .testTag("pane-${pane.id}"),
        shape = HbTheme.shapes.large,
    ) {
        HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            PaneHeader(content, layout, onIntent)
            HbDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val sessionId = pane.sessionId
                val transcript = content.transcript
                when {
                    sessionId == null -> NewSessionHero(content.project, Modifier.align(Alignment.Center))

                    transcript != null -> key(sessionId) {
                        SessionTranscript(
                            sessionId = sessionId,
                            messages = transcript,
                            section = sectionTitle(content.project, content.session),
                            modifier = Modifier.align(Alignment.TopCenter)
                                .widthIn(max = HbTheme.dimensions.chatMessageMaxWidth + HbTheme.spacing.xxl * 2),
                        )
                    }
                }
            }
            if (content.session?.isContinuable == false) {
                HbText(stringResource(Res.string.session_read_only), Modifier.padding(HbTheme.spacing.m))
            } else if (content.session?.isRunning == true && !content.isStoppable) {
                HbText(stringResource(Res.string.stop_unsupported), Modifier.padding(HbTheme.spacing.m))
            }
            if (content.isStopFailed) {
                HbText(stringResource(Res.string.stop_failed), Modifier.padding(HbTheme.spacing.m))
            }
            content.permissions.forEach { request ->
                key(request.requestId) {
                    HbColumn(
                        Modifier.padding(HbTheme.spacing.m)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("permission-${request.requestId}"),
                        gap = HbTheme.spacing.s,
                    ) {
                        HbText(request.title)
                        request.options.forEach { option ->
                            HbButton(
                                text = option.title,
                                onClick = {
                                    onIntent(
                                        AiStudioScreenIntent.RespondPermission(
                                            request.sessionId,
                                            request.requestId,
                                            option.id,
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("permission-${request.requestId}-${option.id}"),
                            )
                        }
                    }
                }
            }
            PaneFooter(content, onIntent, layout.isCompact, onOpenResearch)
        }
    }
}

@Composable
private fun PaneHeader(content: PaneContent, layout: PaneLayout, onIntent: (AiStudioScreenIntent) -> Unit) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val pane = content.pane
    val session = content.session
    val renaming = content.renaming
    HbRow(
        Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
        gap = HbTheme.spacing.xs,
    ) {
        if (layout.isCompact) {
            HbIconButton(
                icon = HbIcons.Menu,
                contentDescription = stringResource(Res.string.pane_open_sidebar),
                onClick = { onIntent(AiStudioScreenIntent.SetDrawerOpen(true)) },
                modifier = Modifier.testTag("pane-open-sidebar"),
            )
        }
        if (renaming != null) {
            RenameField(renaming.title, onIntent, Modifier.weight(1f))
        } else {
            HbText(
                text = session?.title ?: stringResource(Res.string.sidebar_new_session),
                modifier = Modifier.weight(1f).padding(start = HbTheme.spacing.xs),
                style = HbTheme.typography.label,
                color = if (content.isFocused) HbTheme.colors.textPrimary else HbTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        if (session != null) {
            HbMenuButton(
                icon = HbIcons.More,
                contentDescription = stringResource(Res.string.session_actions),
                items = sessionMenu(session, isOpenBesideAllowed = false),
                isExpanded = isMenuOpen,
                onExpandedChange = { isMenuOpen = it },
                onItem = { sessionAction(session, it, paneOrigin(pane.id))?.let(onIntent) },
                modifier = Modifier.testTag("pane-menu-${pane.id}"),
            )
        }
        PaneLayoutActions(pane.id, layout, onIntent)
    }
}

@Composable
private fun PaneLayoutActions(
    paneId: Int,
    layout: PaneLayout,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier, gap = HbTheme.spacing.xs) {
        if (layout.isSplitAllowed) {
            HbIconButton(
                icon = HbIcons.Grid,
                contentDescription = stringResource(Res.string.pane_split),
                onClick = { onIntent(AiStudioScreenIntent.OpenBeside(null)) },
                modifier = Modifier.testTag("pane-split"),
            )
        }
        if (layout.isCloseAllowed) {
            HbIconButton(
                icon = HbIcons.Close,
                contentDescription = stringResource(Res.string.pane_close),
                onClick = { onIntent(AiStudioScreenIntent.ClosePane(paneId)) },
                modifier = Modifier.testTag("pane-close-$paneId"),
            )
        }
    }
}

@Composable
private fun NewSessionHero(project: ProjectUi?, modifier: Modifier = Modifier) {
    HbColumn(
        modifier.hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.xxl)
            .widthIn(max = HbTheme.dimensions.chatMessageMaxWidth)
            .testTag("new-session-hero"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HbIcon(
            icon = HbIcons.Terminal,
            contentDescription = null,
            modifier = Modifier.size(HbTheme.dimensions.iconLargeSize),
            tint = HbTheme.colors.textSecondary,
        )
        HbText(
            text = if (project == null) {
                stringResource(Res.string.new_heading_general)
            } else {
                stringResource(Res.string.new_heading, project.name)
            },
            style = HbTheme.typography.display.copy(textAlign = TextAlign.Center),
        )
        HbText(
            text = stringResource(Res.string.new_hint),
            style = HbTheme.typography.body.copy(textAlign = TextAlign.Center),
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun SessionTranscript(
    sessionId: String,
    messages: ImmutableList<MessageUi>,
    section: String,
    modifier: Modifier = Modifier,
) {
    val timeline = rememberStudioTimeline(sessionId, messages, timelineLabels(section))
    HbChatTranscript(
        timeline = timeline,
        modifier = modifier.fillMaxSize().testTag("transcript-$sessionId"),
        streamingLabel = stringResource(Res.string.streaming),
        jumpToLatestLabel = stringResource(Res.string.jump_latest),
        toolLabels = studioToolLabels(),
    )
}

@Composable
private fun PaneFooter(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    isCompact: Boolean,
    onOpenResearch: ((String) -> Unit)?,
) {
    HbColumn(
        Modifier.fillMaxWidth().padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.s,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val column = Modifier.widthIn(max = HbTheme.dimensions.chatMessageMaxWidth).fillMaxWidth()
        if (content.session?.isRunning == true) RunStatus(content.isStopping, content.elapsed, column)
        if (content.isSubmitFailed) {
            HbBadge(stringResource(Res.string.submit_failed), column, tone = HbTone.Danger)
        }
        if (content.pane.sessionId == null) {
            ContextTray(content.pane, content.project, content.projects, onIntent, column)
        }
        if (content.isResearchAvailable && onOpenResearch != null) {
            HbButton(
                text = stringResource(Res.string.research_mode),
                onClick = { onOpenResearch(content.settings.modelId) },
                modifier = column.testTag("research-mode"),
            )
        }
        StudioComposer(content, onIntent, isCompact, column)
    }
}

@Composable
private fun RunStatus(isStopping: Boolean, elapsed: Duration?, modifier: Modifier = Modifier) {
    HbRow(modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("run-status"), gap = HbTheme.spacing.s) {
        HbActivityIndicator()
        HbText(
            text = if (isStopping || elapsed == null) {
                stringResource(Res.string.stopping)
            } else {
                stringResource(Res.string.working_for, durationLabels().format(elapsed))
            },
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

/** Project and branch of the pane, or the plain conversation label outside projects. */
@Composable
private fun sectionTitle(project: ProjectUi?, session: SessionUi?): String {
    val general = stringResource(Res.string.pane_general)
    return project?.let { "${it.name} · ${session?.branch ?: it.branch}" } ?: general
}

/** Focuses the pane on any press inside it without consuming the gesture. */
private fun Modifier.focusOnPress(isFocused: Boolean, key: Any, onFocus: () -> Unit): Modifier = if (isFocused) {
    this
} else {
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onFocus()
        }
    }
}
