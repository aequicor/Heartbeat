package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbChatTranscript
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.connect_model_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.jump_latest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_heading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_heading_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_close
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_open_sidebar
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_split
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_add_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_model_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.research_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_actions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_read_only
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_running
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_new_session
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stop_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stop_unsupported
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stopping
import io.aequicor.heartbeat.feature.aistudio.impl.resources.streaming
import io.aequicor.heartbeat.feature.aistudio.impl.resources.submit_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_plan_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_review_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests
import io.aequicor.heartbeat.feature.aistudio.impl.resources.template_tests_prompt
import io.aequicor.heartbeat.feature.aistudio.impl.resources.working_for
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * One workspace column: a session transcript or the new-session page, with its composer.
 * [content] holds only this pane's data, so streaming into another pane does not recompose it.
 * Keyboard focus anywhere inside the pane selects it, keeping screen-level screenshot paste on the same draft.
 * Worktree cards follow the transcript; a pane preparing a worktree session already shows them in its feed.
 */
@Composable
internal fun StudioPaneView(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    layout: PaneLayout,
    modifier: Modifier = Modifier,
    onOpenResearch: ((String) -> Unit)? = null,
    isAtWindowLeadingEdge: Boolean = false,
    questions: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    val pane = content.pane
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topInset = with(density) { headerHeight.toDp() }
    val bottomInset = with(density) { footerHeight.toDp() }
    Box(
        modifier.onFocusChanged {
            if (it.hasFocus && !content.isFocused) onIntent(AiStudioScreenIntent.FocusPane(pane.id))
        }.focusGroup().focusOnPress(content.isFocused, pane.id) { onIntent(AiStudioScreenIntent.FocusPane(pane.id)) }
            .background(HbTheme.surfaces.assistant).testTag("pane-${pane.id}"),
    ) {
        val worktreeFeed = content.worktreeFeed()
        val worktree = if (worktreeFeed == null) {
            NoWorktree
        } else {
            val labels = worktreeLabels()
            remember(worktreeFeed, labels) { worktreeTimeline(worktreeFeed, labels) }
        }
        val feedId = pane.sessionId ?: worktreeFeed?.key
        val transcript = content.transcript ?: persistentListOf<MessageUi>().takeIf { worktree.messages.isNotEmpty() }
        val isCenteredComposer = feedId == null && HbTheme.dimensions.isDesktop
        if (feedId == null && !isCenteredComposer) {
            Box(
                Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset),
            ) {
                NewSessionHero(
                    content.project,
                    onDraft = { onIntent(AiStudioScreenIntent.DraftChanged(pane.id, it)) },
                    modifier = Modifier.align(BiasAlignment(0f, HbTheme.dimensions.emptyStateVerticalBias))
                        .padding(HbTheme.spacing.xl),
                )
            }
        } else if (feedId != null && transcript != null) {
            key(feedId) {
                SessionTranscript(
                    sessionId = feedId,
                    messages = transcript,
                    worktree = worktree,
                    section = sectionTitle(content.project, content.session),
                    calendar = content.calendar,
                    attachmentPreviews = content.attachmentPreviews,
                    onIntent = onIntent,
                    contentPadding = PaddingValues(
                        start = HbTheme.spacing.xl,
                        end = HbTheme.spacing.xl,
                        top = topInset + HbTheme.spacing.l,
                        bottom = bottomInset + HbTheme.spacing.l,
                    ),
                    overlapInsets = PaddingValues(top = topInset, bottom = bottomInset),
                    modifier = Modifier.align(Alignment.TopCenter)
                        .widthIn(max = HbTheme.dimensions.messageMaxWidth + HbTheme.spacing.xl * 2)
                        .fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { headerHeight = it.height }
                .testTag("pane-header-${pane.id}"),
        ) {
            PaneHeader(content, layout, onIntent, isAtWindowLeadingEdge)
        }
        // The composer keeps the same parents; changing its placement must not detach its focus target.
        Box(Modifier.fillMaxSize().padding(top = topInset)) {
            PaneComposerRegion(
                content,
                onIntent,
                questions,
                layout.isCompact,
                onOpenResearch,
                isCenteredComposer,
                Modifier.align(
                    composerRegionAlignment(isCenteredComposer),
                ).onSizeChanged { footerHeight = it.height },
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun composerRegionAlignment(isCentered: Boolean): Alignment =
    if (isCentered) BiasAlignment(0f, HbTheme.dimensions.emptyStateVerticalBias) else Alignment.BottomCenter

@Composable
private fun PaneComposerRegion(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    questions: ImmutableMap<String, ComposableComponent>,
    isCompact: Boolean,
    onOpenResearch: ((String) -> Unit)?,
    isCentered: Boolean,
    modifier: Modifier = Modifier,
) {
    val heroScroll = rememberScrollState()
    val regionInset = if (isCentered) {
        Modifier.padding(HbTheme.spacing.xl)
            .widthIn(max = HbTheme.dimensions.composerMaxWidth + HbTheme.spacing.xl * 2)
    } else {
        Modifier
    }
    val heroModifier = if (isCentered) {
        Modifier.hbVerticalScroll(heroScroll).padding(vertical = HbTheme.spacing.xxl).testTag("new-session-hero")
    } else {
        Modifier
    }
    val headingGap = if (isCentered) HbTheme.spacing.xxl + HbTheme.spacing.l else HbTheme.spacing.none
    Box(modifier.then(regionInset).fillMaxWidth()) {
        HbColumn(
            Modifier.fillMaxWidth().then(heroModifier),
            gap = HbTheme.spacing.none,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isCentered) NewSessionHeading(content.project)
            Box(Modifier.fillMaxWidth().padding(top = headingGap)) {
                HbColumn(
                    Modifier.fillMaxWidth().testTag("pane-footer-${content.pane.id}"),
                    gap = HbTheme.spacing.none,
                ) {
                    PaneNotices(content, onIntent, questions)
                    PaneFooter(content, onIntent, isCompact, onOpenResearch)
                }
            }
            if (isCentered) {
                Box(Modifier.padding(top = HbTheme.spacing.l)) {
                    NewSessionStarters { onIntent(AiStudioScreenIntent.DraftChanged(content.pane.id, it)) }
                }
            }
        }
    }
}

@Composable
private fun PaneNotices(
    content: PaneContent,
    onIntent: (AiStudioScreenIntent) -> Unit,
    questions: ImmutableMap<String, ComposableComponent>,
) {
    HbColumn(
        Modifier.fillMaxWidth().background(HbTheme.surfaces.header, HbTheme.shapes.large)
            .pointerInput(Unit) { detectTapGestures { } },
        gap = HbTheme.spacing.none,
    ) {
        if (content.session?.isContinuable == false) {
            HbText(stringResource(Res.string.session_read_only), Modifier.padding(HbTheme.spacing.m))
        } else if (content.session?.isRunning == true && !content.isStoppable) {
            HbText(stringResource(Res.string.stop_unsupported), Modifier.padding(HbTheme.spacing.m))
        }
        if (content.isStopFailed) {
            HbText(stringResource(Res.string.stop_failed), Modifier.padding(HbTheme.spacing.m))
        }
        val asked = content.session?.id?.let(questions::get)
        asked?.Content(Modifier.padding(HbTheme.spacing.m).testTag("pane-questionnaire"))
        content.permissions.takeIf { asked == null }.orEmpty().forEach { request ->
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
    }
}

@Composable
private fun PaneHeader(
    content: PaneContent,
    layout: PaneLayout,
    onIntent: (AiStudioScreenIntent) -> Unit,
    isAtWindowLeadingEdge: Boolean,
) {
    val pane = content.pane
    HbWindowDragArea(Modifier.fillMaxWidth().background(HbTheme.surfaces.header)) {
        HbRow(
            Modifier.fillMaxWidth()
                .heightIn(min = HbTheme.dimensions.headerHeight)
                .background(HbTheme.surfaces.header)
                .padding(
                    start = HbTheme.spacing.m,
                    end = HbTheme.spacing.m,
                    top = HbTheme.spacing.xs,
                    bottom = HbTheme.spacing.xs,
                ),
            gap = HbTheme.spacing.m,
        ) {
            // The open sidebar has its own collapse button; the header offers one only when the sidebar is away.
            if (layout.isCompact || isAtWindowLeadingEdge) {
                HbIconButton(
                    icon = HbIcons.Menu,
                    contentDescription = stringResource(Res.string.pane_open_sidebar),
                    tooltipText = "${stringResource(Res.string.pane_open_sidebar)} ${studioShortcutLabel("\\")}",
                    onClick = {
                        onIntent(
                            if (layout.isCompact) {
                                AiStudioScreenIntent.SetDrawerOpen(true)
                            } else {
                                AiStudioScreenIntent.ToggleSidebar
                            },
                        )
                    },
                    modifier = Modifier.testTag("pane-open-sidebar"),
                )
            }
            PaneTitle(content, onIntent, Modifier.weight(1f))
            PaneSessionMenu(content, onIntent)
            PaneLayoutActions(pane.id, layout, onIntent)
        }
    }
}

/** Header of the chat area while another feature (research) fills it: sidebar toggle, window inset and title. */
@Composable
internal fun ChatAreaHeader(onToggleSidebar: () -> Unit, isAtWindowLeadingEdge: Boolean) {
    val studio = HbTheme.dimensions
    HbWindowDragArea(Modifier.fillMaxWidth().background(HbTheme.surfaces.header)) {
        HbRow(
            Modifier.fillMaxWidth()
                .heightIn(min = studio.headerHeight)
                .background(HbTheme.surfaces.header)
                .padding(
                    start = HbTheme.spacing.m,
                    end = HbTheme.spacing.m,
                    top = HbTheme.spacing.xs,
                    bottom = HbTheme.spacing.xs,
                ),
            gap = HbTheme.spacing.m,
        ) {
            if (isAtWindowLeadingEdge) {
                HbIconButton(
                    icon = HbIcons.Menu,
                    contentDescription = stringResource(Res.string.pane_open_sidebar),
                    tooltipText = "${stringResource(Res.string.pane_open_sidebar)} ${studioShortcutLabel("\\")}",
                    onClick = onToggleSidebar,
                    modifier = Modifier.testTag("chat-area-open-sidebar"),
                )
            }
            HbText(
                text = stringResource(Res.string.research_mode),
                modifier = Modifier.weight(1f),
                style = HbTheme.typography.title,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PaneTitle(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit, modifier: Modifier = Modifier) {
    val renaming = content.renaming
    if (renaming != null) {
        RenameField(renaming.title, onIntent, modifier)
    } else {
        val title = content.session?.title ?: stringResource(Res.string.sidebar_new_session)
        HbTooltip(title, modifier) {
            HbText(
                text = title,
                style = HbTheme.typography.title,
                color = if (content.isFocused) HbTheme.colors.textPrimary else HbTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PaneSessionMenu(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    val session = content.session ?: return
    var isMenuOpen by remember { mutableStateOf(false) }
    HbMenuButton(
        icon = HbIcons.MoreVertical,
        contentDescription = stringResource(Res.string.session_actions),
        items = sessionMenu(session, isOpenBesideAllowed = false),
        isExpanded = isMenuOpen,
        onExpandedChange = { isMenuOpen = it },
        onItem = { sessionAction(session, it, paneOrigin(content.pane.id))?.let(onIntent) },
        modifier = Modifier.testTag("pane-menu-${content.pane.id}"),
    )
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
private fun NewSessionHero(project: ProjectUi?, onDraft: (String) -> Unit, modifier: Modifier = Modifier) {
    HbColumn(
        modifier.widthIn(max = HbTheme.dimensions.composerMaxWidth + HbTheme.spacing.xl * 2)
            .fillMaxWidth().hbVerticalScroll(rememberScrollState()).padding(vertical = HbTheme.spacing.xxl)
            .testTag("new-session-hero"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NewSessionHeading(project)
        NewSessionStarters(onDraft)
    }
}

@Composable
private fun NewSessionHeading(project: ProjectUi?) {
    HbColumn(horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun NewSessionStarters(onDraft: (String) -> Unit) {
    val starters = listOf(
        Triple(Res.string.template_plan, Res.string.template_plan_prompt, HbIcons.Plan),
        Triple(Res.string.template_review, Res.string.template_review_prompt, HbIcons.Search),
        Triple(Res.string.template_tests, Res.string.template_tests_prompt, HbIcons.Check),
    )
    HbFlowRow(Modifier.padding(top = HbTheme.spacing.m), gap = HbTheme.spacing.m) {
        starters.forEach { (label, promptResource, icon) ->
            val prompt = stringResource(promptResource)
            HbChip(label = stringResource(label), icon = icon, onClick = { onDraft(prompt) }, trailingIcon = null)
        }
    }
}

@Composable
private fun SessionTranscript(
    sessionId: String,
    messages: ImmutableList<MessageUi>,
    section: String,
    contentPadding: PaddingValues,
    overlapInsets: PaddingValues,
    calendar: StudioCalendar,
    attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi>,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
    worktree: WorktreeTimeline = WorktreeTimeline(),
) {
    val timeline = rememberStudioTimeline(sessionId, messages, timelineLabels(section, calendar), worktree.messages)
    val links = LocalUriHandler.current
    HbChatTranscript(
        timeline = timeline,
        modifier = modifier.fillMaxSize().testTag("transcript-$sessionId"),
        streamingLabel = stringResource(Res.string.streaming),
        jumpToLatestLabel = stringResource(Res.string.jump_latest),
        toolLabels = studioToolLabels(),
        contentPadding = contentPadding,
        overlapInsets = overlapInsets,
        showSectionHeaders = true,
        onToolAction = { call, action ->
            worktree.dispatch(call.id, action.id, onIntent) { openPullRequest(links, it) }
        },
        messageFooterContent = { rendered ->
            val prompt = messages.firstOrNull { it.id == rendered.id } as? MessageUi.Prompt
            if (prompt != null && prompt.attachments.isNotEmpty()) {
                StudioAttachments(prompt.attachments, onIntent, previews = attachmentPreviews)
            }
        },
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
        Modifier.fillMaxWidth().padding(
            horizontal = if (isCompact) HbTheme.spacing.m else HbTheme.spacing.xl,
            vertical = HbTheme.spacing.m,
        ),
        gap = HbTheme.spacing.s,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val column = Modifier.widthIn(max = HbTheme.dimensions.composerMaxWidth).fillMaxWidth()
        ModelConnectionHint(content, column)
        if (content.session?.isRunning == true) RunStatus(content.isStopping, content.elapsed, column)
        if (content.isSubmitFailed) {
            HbBadge(stringResource(Res.string.submit_failed), column, tone = HbTone.Danger)
        }
        if (content.isProjectFailed) {
            HbBadge(stringResource(Res.string.project_add_failed), column, tone = HbTone.Danger)
        }
        if (content.isAttachmentFailed) {
            HbBadge(
                stringResource(Res.string.attachments_failed),
                column,
                tone = HbTone.Danger,
            )
        }
        if (content.attachments.isNotEmpty()) {
            StudioAttachments(
                content.attachments,
                onIntent,
                column,
                paneId = content.pane.id,
                support = content.models.firstOrNull { it.id == content.settings.modelId }?.inputSupport,
                previews = content.attachmentPreviews,
            )
        }
        StudioComposer(content, onIntent, isCompact, column, onOpenResearch)
    }
}

@Composable
private fun ModelConnectionHint(content: PaneContent, modifier: Modifier = Modifier) {
    val hint = when {
        content.models.isEmpty() -> stringResource(Res.string.connect_model_hint)

        content.project != null && content.models.none { it.id == content.settings.modelId } ->
            stringResource(Res.string.project_model_hint)

        else -> null
    }
    if (hint != null) HbText(hint, modifier, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
}

@Composable
private fun RunStatus(isStopping: Boolean, elapsed: Duration?, modifier: Modifier = Modifier) {
    HbRow(modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("run-status"), gap = HbTheme.spacing.s) {
        HbActivityIndicator()
        HbText(
            text = when {
                isStopping -> stringResource(Res.string.stopping)
                elapsed == null -> stringResource(Res.string.session_running)
                else -> stringResource(Res.string.working_for, durationLabels().format(elapsed))
            },
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textPrimary,
        )
    }
}

private val NoWorktree = WorktreeTimeline()

/** Project and branch of the pane, or the plain conversation label outside projects. */
@Composable
private fun sectionTitle(project: ProjectUi?, session: SessionUi?): String {
    val general = stringResource(Res.string.pane_general)
    return project?.let {
        listOf(
            it.name,
            session?.branch ?: it.branch,
        ).filter(String::isNotBlank).joinToString(" · ")
    } ?: general
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
