package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbChatTranscript
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbDiagramAction
import io.aequicor.heartbeat.ds.components.HbDiagramActionKind
import io.aequicor.heartbeat.ds.components.HbDiagramActionsProvider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.components.HbTranscriptFocus
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.connect_model_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.diagram_draft_busy
import io.aequicor.heartbeat.feature.aistudio.impl.resources.diagram_draft_rejected
import io.aequicor.heartbeat.feature.aistudio.impl.resources.harness_connect_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.jump_latest
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_heading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_heading_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.new_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_close
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_general
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_open_sidebar
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_split
import io.aequicor.heartbeat.feature.aistudio.impl.resources.permission_more
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_add_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_model_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_sessions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_actions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_child_view
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
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.CardFocusRequestUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.DiagramDraftReasonUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanProposalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.paneCards
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioAppKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioComposerSlotKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioContextSlotKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioStartSlotKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/**
 * One workspace column: a session transcript or the new-session page, with its composer.
 * [content] holds only this pane's data, so streaming into another pane does not recompose it.
 * Keyboard focus anywhere inside the pane selects it, keeping screen-level screenshot paste on the same draft.
 * Worktree cards are woven into the transcript at the moment they are invoked; a pane preparing a worktree
 * session already shows them in its feed.
 */
@Composable
internal fun StudioPaneView(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    layout: PaneLayout,
    modifier: Modifier = Modifier,
    onOpenResearch: ((String) -> Unit)? = null,
    isAtWindowLeadingEdge: Boolean = false,
    questions: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    checklists: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    slots: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    cards: ImmutableMap<String, StudioEmbed> = persistentMapOf(),
    timelineState: StudioTimelineState = remember { StudioTimelineState() },
) {
    val pane = content.pane
    val composerSlot = slots[studioComposerSlotKey(pane.id)]
    val startSlot = slots[studioStartSlotKey(pane.id)]
    val appHost = slots[studioAppKey(pane.id)]
    val contextSlot = slots[studioContextSlotKey(pane.id)].takeIf { content.isContextPanelAvailable }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    var panePosition by remember { mutableStateOf(Offset.Zero) }
    var composerPosition by remember { mutableStateOf(Offset.Zero) }
    var contextRowWidth by remember(pane.sessionId, pane.projectId) { mutableStateOf<Int?>(null) }
    val ownCards = remember(cards, pane.id) { paneCards(cards, pane.id).toImmutableMap() }
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topInset = with(density) { headerHeight.toDp() }
    val bottomInset = with(density) { footerHeight.toDp() }
    val feed = paneFeed(content, timelineState)
    val feedId = feed.id
    val hasStartApp = feedId == null && content.app != null
    val isCenteredComposer = feedId == null && HbTheme.dimensions.isDesktop && !hasStartApp
    val placement = contextPanelPlacement(
        with(density) { paneSize.width.toDp() },
        with(density) { contextRowWidth?.toDp() },
        layout.isCompact,
        isCenteredComposer,
    )
    val isBesideAvailable = placement.isBesideAvailable && !hasStartApp
    val context = rememberPaneContextPanel(content, contextSlot, isBesideAvailable)
    val contextInset = placement.insetFor(contextSlot, context.isOpen, isBesideAvailable)
    Box(
        modifier.onSizeChanged { paneSize = it }
            .onGloballyPositioned { panePosition = it.positionInRoot() }.onFocusChanged {
                if (it.hasFocus && !content.isFocused) onIntent(StudioIntent.FocusPane(pane.id))
            }.focusRestorer().focusGroup()
            .focusOnPress(content.isFocused, pane.id) { onIntent(StudioIntent.FocusPane(pane.id)) }
            .background(HbTheme.surfaces.assistant).testTag("pane-${pane.id}"),
    ) {
        PaneLayersLayout(
            reserveContextBand = hasStartApp,
            headerInset = topInset,
            modifier = Modifier.fillMaxSize(),
            conversation = {
                PaneConversation(
                    content,
                    feed,
                    timelineState,
                    onIntent,
                    startSlot,
                    appHost,
                    ownCards,
                    checklists,
                    topInset,
                    bottomInset,
                    isCenteredComposer,
                    hasStartApp,
                    Modifier.fillMaxSize().padding(end = contextInset)
                        .padding(conversationInsets(hasStartApp, layout.isCompact)),
                )
            },
            header = {
                HbColumn(
                    Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                        .testTag("pane-header-${pane.id}"),
                    gap = HbTheme.spacing.none,
                ) {
                    PaneHeader(
                        content,
                        layout,
                        onIntent,
                        isAtWindowLeadingEdge,
                        onToggleContext = context.toggle,
                        isContextOpen = context.isOpen,
                        contextFocus = context.focus,
                    )
                    content.enginePreparations.forEach { StudioEnginePreparation(it) }
                }
            },
            composer = {
                // Its parents stay mounted when the summary opens, grows, or changes responsive placement.
                Box(Modifier.fillMaxSize().padding(top = topInset, end = contextInset)) {
                    PaneComposerRegion(
                        content,
                        onIntent,
                        questions,
                        layout.isCompact,
                        onOpenResearch,
                        isCenteredComposer,
                        PaneSlots(composerSlot, startSlot),
                        onContextWidthChange = { contextRowWidth = it },
                        modifier = Modifier.align(
                            composerRegionAlignment(isCenteredComposer),
                        ).onSizeChanged { footerHeight = it.height }
                            .onGloballyPositioned { composerPosition = it.positionInRoot() },
                    )
                }
            },
            context = {
                PaneContextPanel(
                    contextSlot,
                    context.isOpen,
                    isBesideAvailable,
                    context.dismiss,
                    pane.id,
                    topInset,
                    with(density) { (composerPosition.y - panePosition.y).toDp() },
                    paneSize,
                )
            },
        )
    }
}

/** Standalone start apps use the composer's edge insets, independently of summary availability. */
@Composable
@ReadOnlyComposable
private fun conversationInsets(hasStartApp: Boolean, isCompact: Boolean): PaddingValues {
    val gutter = when {
        !hasStartApp -> HbTheme.spacing.none
        isCompact -> HbTheme.spacing.m
        else -> HbTheme.spacing.xl
    }
    return PaddingValues(horizontal = gutter)
}

/** The conversation and its composer share the same adaptive bounds beside a summary or under its overlay. */
@Composable
private fun PaneConversation(
    content: PaneContent,
    feed: PaneFeed,
    timelineState: StudioTimelineState,
    onIntent: (StudioIntent) -> Unit,
    startSlot: StudioEmbed?,
    appHost: StudioEmbed?,
    ownCards: ImmutableMap<String, StudioEmbed>,
    checklists: ImmutableMap<String, StudioEmbed>,
    topInset: Dp,
    bottomInset: Dp,
    isCenteredComposer: Boolean,
    hasStartApp: Boolean,
    modifier: Modifier = Modifier,
) {
    val feedId = feed.id
    val transcript = feed.messages
    val worktree = feed.worktree
    val pane = content.pane
    var planDialog by remember(content.session?.id) { mutableStateOf<PlanDialog?>(null) }
    val refinePrompt = planRefinePrompt()
    Box(modifier) {
        if (feedId == null && !isCenteredComposer && !hasStartApp) {
            Box(
                Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset),
            ) {
                NewSessionHero(
                    content.project,
                    startSlot,
                    onDraft = { onIntent(StudioIntent.Suggestions.DraftChanged(pane.id, it)) },
                    onPlan = content.planStarter(onIntent),
                    modifier = Modifier.align(BiasAlignment(0f, HbTheme.dimensions.emptyStateVerticalBias))
                        .padding(HbTheme.spacing.xl),
                )
            }
        } else if (feedId != null && transcript != null) {
            key(feedId) {
                SessionTranscript(
                    feedId = feedId,
                    cardFocus = content.cardFocus,
                    onCardFocus = { sequence ->
                        onIntent(StudioIntent.CardFocusApplied(pane.id, sequence))
                    },
                    onDiagramAction = content.diagramActionHandler(onIntent),
                    timelineState = timelineState,
                    messages = transcript,
                    checklists = checklists,
                    cards = ownCards,
                    worktree = worktree,
                    section = sectionTitle(content.project, content.session),
                    calendar = content.calendar,
                    attachmentPreviews = content.attachmentPreviews,
                    isAttachmentOpeningAvailable = content.host.isAttachmentOpeningAvailable,
                    onIntent = onIntent,
                    planCard = { proposal, isLatest ->
                        StudioPlanCard(
                            proposal,
                            content.planning.plan,
                            onApprove = { revision ->
                                content.session?.let { onIntent(StudioIntent.ApprovePlan(it.id, revision)) }
                            },
                            onRefine = {
                                val draft = planRefineDraft(content.draft, refinePrompt)
                                onIntent(StudioIntent.Suggestions.DraftChanged(pane.id, draft))
                            },
                            onDialog = { planDialog = it },
                            decisions = PlanCardDecisions(
                                isLatest = isLatest,
                                isEnabled = content.planning.isEnabled,
                                isBusy = content.isPlanBusy(),
                            ),
                        )
                    },
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
        PlanDialogs(content, planDialog, onIntent) { planDialog = null }
        // This mount stays outside the lazy feed and at the same position when a new chat starts.
        PaneAppWorkspace(
            appHost,
            startSlot,
            hasStartApp,
            if (hasStartApp) Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset) else Modifier,
        )
    }
}

@Immutable
private data class PaneFeed(val id: String?, val worktree: WorktreeTimeline, val messages: ImmutableList<MessageUi>?)

/** Resolve the normal or preparing-worktree transcript independently of the pane's app placement. */
@Composable
private fun paneFeed(content: PaneContent, timelineState: StudioTimelineState): PaneFeed {
    val worktreeFeed = content.worktreeFeed()
    val feedId = content.feedId() ?: worktreeFeed?.key
    val worktree = if (worktreeFeed == null) {
        NoWorktree
    } else {
        val labels = worktreeLabels()
        val shownCards = timelineState.session(feedId ?: worktreeFeed.key).cards.keys
        remember(worktreeFeed, labels, shownCards) { worktreeTimeline(worktreeFeed, labels, shownCards) }
    }
    val transcript = content.transcript ?: persistentListOf<MessageUi>().takeIf { worktree.messages.isNotEmpty() }
    return PaneFeed(feedId, worktree, transcript)
}

/** One full app body on start, a zero-layout modal host in a chat, and any unrelated legacy start blocks. */
@Composable
private fun PaneAppWorkspace(app: StudioEmbed?, start: StudioEmbed?, isStart: Boolean, modifier: Modifier = Modifier) {
    HbColumn(modifier) {
        app?.Content(if (isStart) Modifier.weight(1f).fillMaxWidth() else Modifier)
        if (isStart) {
            start?.Content(
                Modifier.fillMaxWidth().heightIn(max = HbTheme.dimensions.toolPayloadMaxHeight)
                    .hbVerticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun composerRegionAlignment(isCentered: Boolean): Alignment =
    if (isCentered) BiasAlignment(0f, HbTheme.dimensions.emptyStateVerticalBias) else Alignment.BottomCenter

@Composable
@ReadOnlyComposable
private fun composerHeadingGap(isCentered: Boolean): Dp =
    if (isCentered) HbTheme.spacing.xxl + HbTheme.spacing.l else HbTheme.spacing.none

/** A nested questionnaire owns its scroll and must receive the finite viewport captured above the outer scroll. */
@Composable
@ReadOnlyComposable
private fun questionViewportHeight(maxHeight: Dp): Dp =
    if (maxHeight == Dp.Infinity) HbTheme.dimensions.toolPayloadMaxHeight else maxHeight

@Composable
private fun PaneComposerRegion(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    questions: ImmutableMap<String, StudioEmbed>,
    isCompact: Boolean,
    onOpenResearch: ((String) -> Unit)?,
    isCentered: Boolean,
    slots: PaneSlots,
    onContextWidthChange: (Int) -> Unit,
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
    val headingGap = composerHeadingGap(isCentered)
    Box(modifier.then(regionInset).fillMaxWidth()) {
        HbColumn(
            Modifier.fillMaxWidth().then(heroModifier),
            gap = HbTheme.spacing.none,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val noticesModifier = if (isCentered) Modifier else Modifier.weight(1f, fill = false)
            if (isCentered) NewSessionHeading(content.project)
            Box(Modifier.fillMaxWidth().padding(top = headingGap)) {
                HbColumn(
                    Modifier.fillMaxWidth().testTag("pane-footer-${content.pane.id}"),
                    gap = HbTheme.spacing.none,
                ) {
                    PaneNotices(
                        content,
                        onIntent,
                        questions,
                        isCompact,
                        noticesModifier,
                    )
                    PaneFooter(content, onIntent, isCompact, onOpenResearch, slots.composer, onContextWidthChange)
                }
            }
            if (isCentered) {
                Box(Modifier.padding(top = HbTheme.spacing.l)) {
                    NewSessionStarters(content.planStarter(onIntent)) {
                        onIntent(StudioIntent.Suggestions.DraftChanged(content.pane.id, it))
                    }
                }
                slots.start?.Content(StartSlotModifier.padding(top = HbTheme.spacing.l))
            }
        }
    }
}

@Composable
private fun PaneNotices(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    questions: ImmutableMap<String, StudioEmbed>,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) = HbBoxWithConstraints(modifier.fillMaxWidth()) {
    // Descriptions share a part of the pane, so long ones never push the decisions or the composer off a low pane.
    val requests = content.permissions.map { it.description } +
        content.organism?.permissions.orEmpty().map { it.description }
    val described = requests.count { !it.isNullOrBlank() }.coerceAtLeast(1)
    val descriptionMaxHeight = if (constraints.hasBoundedHeight) {
        minOf(HbTheme.dimensions.toolPayloadMaxHeight, maxHeight / (PANE_SHARE_OF_DESCRIPTIONS * described))
    } else {
        HbTheme.dimensions.toolPayloadMaxHeight
    }
    val questionMaxHeight = questionViewportHeight(maxHeight)
    val scroll = rememberScrollState()
    // All notices share the space left by the composer; the centered start page already has an outer scroll.
    val scrolling = if (constraints.hasBoundedHeight) Modifier.hbVerticalScroll(scroll) else Modifier
    HbColumn(
        Modifier.fillMaxWidth().then(scrolling).background(HbTheme.surfaces.header, HbTheme.shapes.large)
            .pointerInput(Unit) { detectTapGestures { } },
        gap = HbTheme.spacing.none,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PaneSessionNotices(content, descriptionMaxHeight, onIntent)
        val asked = content.session?.id?.let(questions::get)
        asked?.Content(
            Modifier.heightIn(max = questionMaxHeight)
                .padding(
                    horizontal = if (isCompact) HbTheme.spacing.m else HbTheme.spacing.xl,
                    vertical = HbTheme.spacing.m,
                )
                .widthIn(max = HbTheme.dimensions.composerMaxWidth).fillMaxWidth().testTag("pane-questionnaire"),
        )
        content.permissions.takeIf { asked == null }.orEmpty().forEach { request ->
            key(request.requestId) {
                HbColumn(
                    Modifier.padding(HbTheme.spacing.m).testTag("permission-${request.requestId}"),
                    gap = HbTheme.spacing.s,
                ) {
                    // Only the title is announced: the reviewed content can be thousands of characters long.
                    HbText(request.title, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    request.description?.takeIf { it.isNotBlank() }?.let { description ->
                        PermissionDescription(description, request.requestId, descriptionMaxHeight)
                    }
                    request.options.forEach { option ->
                        HbButton(
                            text = option.title,
                            onClick = {
                                onIntent(
                                    StudioIntent.RespondPermission(
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
        FooterNotices(
            content,
            onIntent,
            Modifier.padding(horizontal = if (isCompact) HbTheme.spacing.m else HbTheme.spacing.xl)
                .widthIn(max = HbTheme.dimensions.composerMaxWidth).fillMaxWidth(),
        )
    }
}

@Composable
private fun PaneSessionNotices(content: PaneContent, descriptionMaxHeight: Dp, onIntent: (StudioIntent) -> Unit) {
    EngineSwitchNotice(content.engineSwitch)
    if (content.isNativeChild) {
        StudioNotice(content, StudioNoticeUi.NativeChild, stringResource(Res.string.session_child_view), onIntent)
    } else if (content.session?.isOrganism == true) {
        OrganismNotices(content, descriptionMaxHeight, onIntent)
    } else if (content.session?.isContinuable == false) {
        StudioNotice(content, StudioNoticeUi.ReadOnly, stringResource(Res.string.session_read_only), onIntent)
    } else if (content.session?.isRunning == true && !content.isStoppable) {
        StudioNotice(content, StudioNoticeUi.StopUnsupported, stringResource(Res.string.stop_unsupported), onIntent)
    }
    if (content.isStopFailed) {
        StudioNotice(
            content,
            StudioNoticeUi.StopFailed,
            stringResource(Res.string.stop_failed),
            onIntent,
            tone = HbTone.Danger,
        )
    }
}

/** Panes this many times taller than all permission descriptions together still show decisions and the composer. */
private const val PANE_SHARE_OF_DESCRIPTIONS = 3

/**
 * What a permission request approves, bounded and scrollable. A long text keeps a line saying so: the scrollbar
 * alone appears only on hover, and the hidden part is part of what the user approves. The text area takes focus,
 * so a keyboard scrolls it with the arrow and page keys.
 */
@Composable
internal fun PermissionDescription(description: String, requestId: String, maxHeight: Dp) {
    val scroll = rememberScrollState()
    // Kept while the text is scrollable at all, so the decisions do not move once the end is reached.
    val isLong by remember(scroll) { derivedStateOf { scroll.maxValue in 1 until Int.MAX_VALUE } }
    HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        Box(
            Modifier.fillMaxWidth()
                .heightIn(max = maxHeight)
                .hbVerticalScroll(scroll)
                .focusable()
                .testTag("permission-$requestId-scroll"),
        ) {
            HbText(description, Modifier.testTag("permission-$requestId-description"))
        }
        if (isLong) {
            HbText(
                stringResource(Res.string.permission_more),
                Modifier.testTag("permission-$requestId-more"),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PaneHeader(
    content: PaneContent,
    layout: PaneLayout,
    onIntent: (StudioIntent) -> Unit,
    isAtWindowLeadingEdge: Boolean,
    onToggleContext: (() -> Unit)?,
    isContextOpen: Boolean,
    contextFocus: FocusRequester,
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
                                StudioIntent.SetDrawerOpen(true)
                            } else {
                                StudioIntent.ToggleSidebar
                            },
                        )
                    },
                    modifier = Modifier.testTag("pane-open-sidebar"),
                )
            }
            PaneTitle(content, onIntent, Modifier.weight(1f))
            OrganismSwitcher(content, onIntent)
            PaneSessionMenu(content, onIntent)
            PaneContextToggle(
                onToggleContext,
                isContextOpen,
                layout.isCompact || layout.isCloseAllowed,
                pane.id,
                contextFocus,
            )
            PaneLayoutActions(pane.id, layout, onIntent)
        }
    }
}

/** Header shared by workspace features: sidebar toggle, window inset, title and return to chats. */
@Composable
internal fun ChatAreaHeader(
    onToggleSidebar: () -> Unit,
    isAtWindowLeadingEdge: Boolean,
    title: String,
    onClose: (() -> Unit)?,
) {
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
                text = title,
                modifier = Modifier.weight(1f),
                style = HbTheme.typography.title,
                maxLines = 1,
            )
            onClose?.let { close ->
                HbIconButton(
                    icon = HbIcons.Chats,
                    contentDescription = stringResource(Res.string.rail_sessions),
                    onClick = close,
                    modifier = Modifier.testTag("chat-area-close"),
                )
            }
        }
    }
}

@Composable
private fun PaneTitle(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val renaming = content.renaming
    if (renaming != null) {
        RenameField(renaming.title, onIntent, modifier)
    } else {
        val title = listOfNotNull(
            content.session?.title ?: stringResource(Res.string.sidebar_new_session),
            content.session?.checklistStatusLabel(),
        ).joinToString(" · ")
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
private fun PaneSessionMenu(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
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
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier, gap = HbTheme.spacing.xs) {
        if (layout.isSplitAllowed) {
            HbIconButton(
                icon = HbIcons.Grid,
                contentDescription = stringResource(Res.string.pane_split),
                onClick = { onIntent(StudioIntent.OpenBeside(null)) },
                modifier = Modifier.testTag("pane-split"),
            )
        }
        if (layout.isCloseAllowed) {
            HbIconButton(
                icon = HbIcons.Close,
                contentDescription = stringResource(Res.string.pane_close),
                onClick = { onIntent(StudioIntent.ClosePane(paneId)) },
                modifier = Modifier.testTag("pane-close-$paneId"),
            )
        }
    }
}

@Composable
private fun NewSessionHero(
    project: ProjectUi?,
    startSlot: StudioEmbed?,
    onDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlan: (() -> Unit)? = null,
) {
    HbColumn(
        modifier.widthIn(max = HbTheme.dimensions.composerMaxWidth + HbTheme.spacing.xl * 2)
            .fillMaxWidth().hbVerticalScroll(rememberScrollState()).padding(vertical = HbTheme.spacing.xxl)
            .testTag("new-session-hero"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NewSessionHeading(project)
        NewSessionStarters(onPlan, onDraft)
        startSlot?.Content(StartSlotModifier.padding(top = HbTheme.spacing.l))
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

/** Starter drafts of a new chat; with [onPlan] the plan starter also switches the chat to planning mode. */
@Composable
private fun NewSessionStarters(onPlan: (() -> Unit)?, onDraft: (String) -> Unit) {
    val starters = listOf(
        NewSessionStarter(Res.string.template_plan, Res.string.template_plan_prompt, HbIcons.Plan, isPlan = true),
        NewSessionStarter(Res.string.template_review, Res.string.template_review_prompt, HbIcons.Search),
        NewSessionStarter(Res.string.template_tests, Res.string.template_tests_prompt, HbIcons.Check),
    )
    HbFlowRow(Modifier.padding(top = HbTheme.spacing.m), gap = HbTheme.spacing.m) {
        starters.forEach { starter ->
            val prompt = stringResource(starter.prompt)
            HbChip(
                label = stringResource(starter.label),
                icon = starter.icon,
                onClick = {
                    if (starter.isPlan) onPlan?.invoke()
                    onDraft(prompt)
                },
                trailingIcon = null,
                modifier = Modifier.testTag(if (starter.isPlan) "starter-plan" else "starter-${starter.label.key}"),
            )
        }
    }
}

/** A starter draft of a new chat; [isPlan] also switches the chat to planning mode. */
private data class NewSessionStarter(
    val label: StringResource,
    val prompt: StringResource,
    val icon: ImageVector,
    val isPlan: Boolean = false,
)

/** Switches a new chat to planning mode from its plan starter, while planning is offered. */
private fun PaneContent.planStarter(onIntent: (StudioIntent) -> Unit): (() -> Unit)? =
    { onIntent(StudioIntent.SelectPlanning(pane.id, true)) }.takeIf { planningSelection() == false }

/** Feed [feedId]: the open session, or `pane-<id>` while the pane only shows its [worktree] cards. */
@Composable
private fun SessionTranscript(
    feedId: String,
    cardFocus: CardFocusRequestUi?,
    onCardFocus: (Long) -> Unit,
    onDiagramAction: ((HbDiagramAction) -> Unit)?,
    timelineState: StudioTimelineState,
    messages: ImmutableList<MessageUi>,
    worktree: WorktreeTimeline,
    checklists: ImmutableMap<String, StudioEmbed>,
    cards: ImmutableMap<String, StudioEmbed>,
    section: String,
    contentPadding: PaddingValues,
    overlapInsets: PaddingValues,
    calendar: StudioCalendar,
    attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi>,
    isAttachmentOpeningAvailable: Boolean,
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
    planCard: (@Composable (proposal: PlanProposalUi, isLatest: Boolean) -> Unit)? = null,
) {
    var preparedFocus by remember { mutableStateOf<Long?>(null) }
    val focusTarget = cardFocus?.takeIf { request ->
        messages.any { it is MessageUi.Reply && it.id == request.messageId && request.cardId in it.cardIds }
    }
    val latestOnCardFocus by rememberUpdatedState(onCardFocus)
    key(cardFocus, focusTarget) {
        SideEffect {
            if (cardFocus != null && focusTarget == null) latestOnCardFocus(cardFocus.sequence)
        }
    }
    val timeline = rememberStudioTimeline(
        feedId,
        timelineState,
        messages,
        timelineLabels(section, calendar),
        worktree.messages,
        worktree.retained,
    )
    val links = LocalUriHandler.current
    // Only the last card of a revision shows the full plan and its decisions; repeated proposals only open it.
    val latestPlans = remember(messages) { latestPlanProposals(messages) }
    HbDiagramActionsProvider(onDiagramAction) {
        HbChatTranscript(
            timeline = timeline,
            modifier = modifier.fillMaxSize().testTag("transcript-$feedId"),
            streamingLabel = stringResource(Res.string.streaming),
            jumpToLatestLabel = stringResource(Res.string.jump_latest),
            focusRequest = focusTarget?.let { HbTranscriptFocus(it.messageId, it.sequence, isEmbedded = true) },
            onFocus = { preparedFocus = it },
            toolLabels = studioToolLabels(),
            contentPadding = contentPadding,
            overlapInsets = overlapInsets,
            showSectionHeaders = true,
            onToolAction = { call, action ->
                worktree.dispatch(call.id, action.id, onIntent) { openPullRequest(links, it) }
            },
            messageEmbeddedContent = { rendered ->
                val reply = messages.firstOrNull { it.id == rendered.id } as? MessageUi.Reply
                HbColumn(gap = HbTheme.spacing.m) {
                    reply?.tools?.lastOrNull { it.plan != null }?.plan?.let { proposal ->
                        planCard?.invoke(
                            proposal,
                            proposal.revision != null && latestPlans[proposal.revision] == reply.id,
                        )
                    }
                    reply?.checklistIds?.forEach { id -> key(id) { checklists[id]?.Content(Modifier) } }
                    reply?.cardIds?.forEach { id ->
                        key("card:$id") {
                            cards[id]?.let { card ->
                                StudioEmbeddedCard(
                                    card,
                                    focusTarget?.takeIf { it.cardId == id && it.sequence == preparedFocus },
                                    onCardFocus,
                                )
                            }
                        }
                    }
                }
            },
            messageFooterContent = { rendered ->
                val prompt = messages.firstOrNull { it.id == rendered.id } as? MessageUi.Prompt
                if (prompt != null && prompt.attachments.isNotEmpty()) {
                    StudioAttachments(
                        prompt.attachments,
                        onIntent,
                        previews = attachmentPreviews,
                        isOpeningAvailable = isAttachmentOpeningAvailable,
                    )
                }
            },
        )
    }
}

@Composable
private fun PaneFooter(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    isCompact: Boolean,
    onOpenResearch: ((String) -> Unit)?,
    composerSlot: StudioEmbed?,
    onContextWidthChange: (Int) -> Unit,
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
        PaneExecutionStatus(content, column)
        if (content.attachments.isNotEmpty()) {
            StudioAttachments(
                content.attachments,
                onIntent,
                column,
                paneId = content.pane.id,
                support = content.models.firstOrNull { it.id == content.settings.modelId }?.inputSupport,
                previews = content.attachmentPreviews,
                isOpeningAvailable = content.host.isAttachmentOpeningAvailable,
            )
        }
        // Interfaces of the harnesses active in the chat sit right above its input; a tall one scrolls, so the
        // input stays on screen.
        val slotScroll = rememberScrollState()
        composerSlot?.Content(
            column.heightIn(max = HbTheme.dimensions.toolPayloadMaxHeight).hbVerticalScroll(slotScroll),
        )
        StudioComposer(content, onIntent, isCompact, column, onOpenResearch, onContextWidthChange)
    }
}

@Composable
private fun PaneExecutionStatus(content: PaneContent, modifier: Modifier = Modifier) {
    if (content.session?.isRunning == true) {
        RunStatus(content.isStopping, content.elapsed, modifier)
    } else if (content.session?.isWaiting == true) {
        SessionWaitStatus(content.session, modifier)
    }
}

/** Passive banners share only the height left after the editor and its controls have been measured. */
@Composable
private fun FooterNotices(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    HbColumn(modifier.testTag("footer-notices-${content.pane.id}"), gap = HbTheme.spacing.s) {
        content.projectTrust?.let { ProjectTrustNotice(it, onIntent) }
        ModelConnectionHint(content, onIntent)
        DiagramDraftNotice(content, onIntent)
        if (content.isSubmitFailed) {
            StudioNotice(
                content,
                StudioNoticeUi.SubmitFailed,
                stringResource(Res.string.submit_failed),
                onIntent,
                tone = HbTone.Danger,
            )
        }
        if (content.isProjectFailed) {
            StudioNotice(
                content,
                StudioNoticeUi.ProjectFailed,
                stringResource(Res.string.project_add_failed),
                onIntent,
                tone = HbTone.Danger,
            )
        }
        if (content.isAttachmentFailed) {
            StudioNotice(
                content,
                StudioNoticeUi.AttachmentFailed,
                stringResource(Res.string.attachments_failed),
                onIntent,
                tone = HbTone.Danger,
            )
        }
        if (content.isHarnessFailed) {
            StudioNotice(
                content,
                StudioNoticeUi.HarnessFailed,
                stringResource(Res.string.harness_connect_failed),
                onIntent,
                tone = HbTone.Danger,
            )
        }
    }
}

@Composable
private fun ModelConnectionHint(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    when {
        content.models.isEmpty() -> StudioNotice(
            content,
            StudioNoticeUi.ConnectModel,
            stringResource(Res.string.connect_model_hint),
            onIntent,
            modifier,
        )

        content.project != null && content.models.none { it.id == content.settings.modelId } -> StudioNotice(
            content,
            StudioNoticeUi.ProjectModel,
            stringResource(Res.string.project_model_hint),
            onIntent,
            modifier,
        )
    }
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

/** Interface slots of one pane: the row above its input and the start window of a new chat. */
@Immutable
private data class PaneSlots(val composer: StudioEmbed?, val start: StudioEmbed?)

private val StartSlotModifier: Modifier
    @Composable @ReadOnlyComposable
    get() = Modifier.widthIn(max = HbTheme.dimensions.composerMaxWidth).fillMaxWidth()

private fun PaneContent.diagramActionHandler(onIntent: (StudioIntent) -> Unit): ((HbDiagramAction) -> Unit)? =
    session?.takeIf { it.isContinuable && subSession == PRIMARY_SUB_SESSION && !diagram.isPending }?.let { source ->
        { action ->
            onIntent(
                StudioIntent.DiagramAction(source.id, action.text, action.kind == HbDiagramActionKind.Send),
            )
        }
    }

@Composable
private fun DiagramDraftNotice(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val reason = content.diagram.draftReason ?: return
    val message = when (reason) {
        DiagramDraftReasonUi.Busy -> Res.string.diagram_draft_busy
        DiagramDraftReasonUi.Rejected -> Res.string.diagram_draft_rejected
    }
    StudioNotice(content, StudioNoticeUi.DiagramDraft, stringResource(message), onIntent, modifier)
}

/**
 * The plan editor or implementation dialog the pane shows for the plan of its chat. An implementation dialog that no
 * longer applies (no plan or chat, planning switched off, or a plan that is not planned any more) is dropped. The
 * editor of a plan that is no longer available closes unless it holds unsaved edits: then it stays on the last known
 * plan without saving, so the user can copy or cancel the edit.
 */
@Composable
private fun PlanDialogs(
    content: PaneContent,
    dialog: PlanDialog?,
    onIntent: (StudioIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = content.planning.plan
    val sessionId = content.session?.id
    val isAvailable = plan != null && sessionId != null && content.planning.isEnabled
    var lastPlan by remember { mutableStateOf(plan) }
    PlanEffect(plan) { if (plan != null) lastPlan = plan }
    val isStale = dialog == PlanDialog.Implement && (!isAvailable || !plan.isPlanning)
    PlanEffect(isStale) { if (isStale) onDismiss() }
    when {
        dialog == PlanDialog.Editor -> PlanEditorDialog(plan ?: lastPlan, isAvailable, onDismiss) { text, base ->
            sessionId?.let { onIntent(StudioIntent.SavePlan(it, base, text)) }
        }

        dialog == PlanDialog.Implement && !isStale && plan != null && sessionId != null -> StudioPlanImplementDialog(
            plan,
            content.planImplementationOffer(),
            sessionId,
            onImplement = {
                onIntent(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** The editor of the [shown] plan, the last known one while it is not [isAvailable]; without any plan it closes. */
@Composable
private fun PlanEditorDialog(
    shown: PlanUi?,
    isAvailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (text: String, base: Long) -> Unit,
) {
    if (shown == null) {
        PlanEffect(Unit) { onDismiss() }
    } else {
        StudioPlanEditor(shown, onSave, onDismiss, isAvailable = isAvailable)
    }
}

/** What the implementation dialog offers for the chat of this pane: its route, approvals and modes. */
internal fun PaneContent.planImplementationOffer(): PlanImplementationOffer {
    val model = models.firstOrNull { it.id == settings.modelId }
    val effort = if (configuration != null) {
        settings.nativeEffort
    } else {
        model?.let { settings.engineEfforts[it.id] }
    }
    return PlanImplementationOffer(
        planModels,
        settings.modelId,
        settings.approval,
        planning.modes,
        effort = effort,
        securityLevel = profileSecurityLevel(),
        isTrustSupported = isTrustSupported(),
        isBusy = isPlanBusy(),
    )
}

/** A turn, a configuration change or the creation of the chat is in progress, so its plan is not implemented now. */
private fun PaneContent.isPlanBusy(): Boolean = pane.isCreating || session?.isRunning == true || isSettingPending
