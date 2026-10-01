package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.hbStickyHeader
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

private val timelineLog = Log.tag("DS/SectionedTranscript")

/** Logs the timeline size once per distinct pair of counts; streaming rewrites only the tail. */
private class TimelineChangeLog {
    private var counts = -1 to -1

    fun log(messageCount: Int, itemCount: Int) {
        val next = messageCount to itemCount
        if (next == counts) return
        counts = next
        timelineLog.d { "timeline updated messages=${next.first} rows=${next.second}" }
    }
}

/**
 * Chronological, sectioned chat with pinned headers and individually virtualized message chunks.
 * Prepare [timeline] outside composition. Streaming only replaces the tail; history retains stable keys.
 * [messageAppearance] applies presentation preferences only to visible rows without mapping the history.
 * [contentPadding] reserves readable space beneath floating controls without shrinking the scroll viewport.
 * [showSectionHeaders] can hide date/session headings when their context is already shown outside the transcript.
 * [overlapInsets] softly fades rows beneath floating controls without fading the scrollbar or jump action.
 */
@Composable
public fun HbChatTranscript(
    timeline: HbChatTimeline,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (timeline.itemCount - 1).coerceAtLeast(0),
    ),
    streamingLabel: String = "",
    jumpToLatestLabel: String = "",
    onJumpToLatest: () -> Unit = {},
    messageAppearance: ((HbChatMessage) -> HbMessageAppearance)? = null,
    toolLabels: HbToolLabels = HbToolLabels(),
    toolExpansionState: HbToolExpansionState = rememberHbToolExpansionState(),
    onLinkClick: ((String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(HbTheme.spacing.l),
    showSectionHeaders: Boolean = true,
    overlapInsets: PaddingValues = PaddingValues(),
    messageFooterContent: (@Composable (HbChatMessage) -> Unit)? = null,
) {
    val expandedKeys = toolExpansionState.expandedKeys
    val sections = remember(timeline, expandedKeys) { expandedTranscriptSections(timeline, expandedKeys) }
    val displayedItemCount = sections.sumOf {
        it.itemCount + if (showSectionHeaders && it.source.section.title.isNotBlank()) 1 else 0
    }
    PreserveDisclosureAnchor(state, sections, expandedKeys, showSectionHeaders)
    // Saved with the list position: a restored reader keeps their decision instead of guessing it from (0, 0).
    val followStateHolder = rememberSaveable(state, stateSaver = ChatFollowStateSaver) {
        mutableStateOf(
            ChatFollowState(timeline.itemCount == 0 || state.firstVisibleItemIndex >= displayedItemCount - 1),
        )
    }
    var followState by followStateHolder
    val followingScrollHolder = remember(state) { mutableStateOf(false) }
    var isFollowingScroll by followingScrollHolder
    val atLatestHolder = remember(state) { derivedStateOf { !state.canScrollForward } }
    val isAtLatest by atLatestHolder
    val scope = rememberCoroutineScope()
    val isReducedMotion = HbTheme.motion.isReducedMotion
    val latestItemCountHolder = rememberUpdatedState(displayedItemCount)
    val latestItemCount by latestItemCountHolder
    // Streaming rewrites the tail on every revision; the log marks real growth, not each rewrite.
    val timelineChangeLog = remember { TimelineChangeLog() }

    LaunchedEffect(state) {
        state.trackTimelineFollow(followStateHolder, followingScrollHolder, atLatestHolder, latestItemCountHolder)
    }
    LaunchedEffect(state, timeline.latestMessage, timeline.itemCount, contentPadding, showSectionHeaders) {
        timelineChangeLog.log(timeline.messageCount, timeline.itemCount)
        if (timeline.messageCount == 0) followState = ChatFollowState()
        if (timeline.itemCount > 0 && followState.shouldScrollOnUpdate(state.isScrollInProgress)) {
            isFollowingScroll = true
            try {
                state.moveToTimelineEnd(displayedItemCount - 1, isAnimated = false)
            } finally {
                isFollowingScroll = false
            }
        }
    }

    Box(modifier = modifier) {
        HbStickyHeaderHost(
            state = state,
            stickyHeaderKeyPrefix = "section:",
            modifier = Modifier.fillMaxSize(),
            overlapInsets = overlapInsets,
            header = { key, headerModifier ->
                // Layout info may retain the previous key until a replaced timeline is measured.
                timeline.sections.firstOrNull { "section:${it.section.id}" == key }?.section?.let { section ->
                    HbTranscriptSectionHeader(section, headerModifier)
                }
            },
        ) { headerContent ->
            HbLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = state,
                gap = HbTheme.elevation.none,
                contentPadding = contentPadding,
                showScrollbar = false,
            ) {
                sections.forEach { section ->
                    hbTranscriptSectionHeader(section.source.section, showSectionHeaders, headerContent)
                    hbExpandedTimelineItems(section) { chunk ->
                        val message = timeline.message(chunk.messageId)
                        TimelineMessageChunk(
                            chunk = chunk,
                            message = message,
                            streamingLabel = streamingLabel,
                            toolLabels = toolLabels,
                            onLinkClick = onLinkClick,
                            footerContent = messageFooterContent,
                            appearance = messageAppearance?.invoke(message),
                            isLatestMessage = message.id == timeline.latestMessage?.id,
                            isToolExpanded = chunk.key in expandedKeys,
                            onToolExpandedChange = { isExpanded ->
                                followState = followState.onUserScroll(isAtLatest = !isExpanded && isAtLatest)
                                toolExpansionState.updateTool(chunk, isExpanded)
                            },
                        )
                    }
                }
            }
        }
        if (!isAtLatest && jumpToLatestLabel.isNotBlank()) {
            HbButton(
                text = jumpToLatestLabel,
                onClick = {
                    timelineLog.i { "jump to latest requested" }
                    followState = followState.onJumpToLatest()
                    scope.launch {
                        isFollowingScroll = true
                        try {
                            state.moveToTimelineEnd(displayedItemCount - 1, isAnimated = !isReducedMotion)
                            state.moveToTimelineEnd(latestItemCount - 1, isAnimated = false)
                        } finally {
                            isFollowingScroll = false
                            followState = followState.onUserScroll(!state.canScrollForward)
                        }
                    }
                    onJumpToLatest()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(
                    start = HbTheme.spacing.m,
                    top = HbTheme.spacing.m,
                    end = HbTheme.spacing.m,
                    bottom = contentPadding.calculateBottomPadding() + HbTheme.spacing.m,
                ),
                style = HbButtonStyle.Secondary,
            )
        }
    }
}

private fun LazyListScope.hbTranscriptSectionHeader(
    section: HbChatSection,
    isVisible: Boolean,
    content: @Composable (String) -> Unit,
) {
    if (!isVisible || section.title.isBlank()) return
    if (section.isDate) {
        item(key = "date:${section.id}", contentType = "date") { HbTranscriptDateHeader(section) }
    } else {
        val key = "section:${section.id}"
        hbStickyHeader(key = key) { content(key) }
    }
}

private fun HbToolExpansionState.updateTool(chunk: HbTranscriptChunk, isExpanded: Boolean) {
    val tool = chunk.body as? HbTranscriptBody.Tool ?: return
    setExpanded(chunk.messageId, tool.call.id, isExpanded)
}

@Composable
private fun TimelineMessageChunk(
    chunk: HbTranscriptChunk,
    message: HbChatMessage,
    streamingLabel: String,
    toolLabels: HbToolLabels,
    onLinkClick: ((String) -> Unit)?,
    footerContent: (@Composable (HbChatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    appearance: HbMessageAppearance? = null,
    isLatestMessage: Boolean = false,
    isToolExpanded: Boolean = false,
    onToolExpandedChange: (Boolean) -> Unit = {},
) {
    // Keep per-row snapshots cheap: only the footer needs the complete copyable prose.
    val copyText = if (chunk.isLast && (appearance ?: message.appearance).isUnified) {
        remember(message) {
            if (message.parts.isEmpty()) {
                message.text
            } else {
                message.parts.filterIsInstance<HbMessagePart.Text>().joinToString("\n\n") { it.text }
            }
        }
    } else {
        ""
    }
    io.aequicor.heartbeat.ds.layouts.HbColumn(
        modifier = modifier.padding(
            bottom = if (chunk.isLast && !isLatestMessage) HbTheme.spacing.l else HbTheme.elevation.none,
        ),
    ) {
        HbTranscriptChunkContent(
            chunk = chunk,
            message = message.copy(
                text = copyText,
                parts = persistentListOf(),
                toolCalls = persistentListOf(),
                appearance = appearance ?: message.appearance,
            ),
            streamingLabel = streamingLabel,
            toolLabels = toolLabels,
            onLinkClick = onLinkClick,
            isToolExpanded = isToolExpanded,
            onToolExpandedChange = onToolExpandedChange,
        )
        if (chunk.isLast) footerContent?.invoke(message)
    }
}

/**
 * Any movement the transcript did not cause is the reader's choice: scrollbar seeks and semantics actions
 * scroll synchronously and never expose isScrollInProgress. While following, a shrinking viewport
 * (keyboard, growing composer, resize) or content growth is re-anchored to the end.
 */
private suspend fun LazyListState.trackTimelineFollow(
    followState: MutableState<ChatFollowState>,
    isFollowingScroll: MutableState<Boolean>,
    isAtLatest: State<Boolean>,
    latestItemCount: State<Int>,
) {
    var previousPosition = firstVisibleItemIndex to firstVisibleItemScrollOffset
    snapshotFlow {
        TranscriptScrollActivity(
            isScrolling = isScrollInProgress,
            position = firstVisibleItemIndex to firstVisibleItemScrollOffset,
            isAtLatest = isAtLatest.value,
        )
    }.collect { activity ->
        val hasMoved = activity.position != previousPosition
        previousPosition = activity.position
        val isReaderMove = activity.isScrolling || hasMoved
        val isDetached = followState.value.isFollowingLatest && !activity.isAtLatest && latestItemCount.value > 0
        when {
            isFollowingScroll.value -> Unit

            isReaderMove -> followState.value = updateTimelineFollowState(followState.value, activity.isAtLatest)

            isDetached -> {
                isFollowingScroll.value = true
                try {
                    moveToTimelineEnd(latestItemCount.value - 1, isAnimated = false)
                } finally {
                    isFollowingScroll.value = false
                }
            }
        }
    }
}

private fun updateTimelineFollowState(previous: ChatFollowState, isAtLatest: Boolean): ChatFollowState {
    val next = previous.onUserScroll(isAtLatest)
    if (next != previous) timelineLog.d { "follow latest=${next.isFollowingLatest}" }
    return next
}

private data class TranscriptScrollActivity(
    val isScrolling: Boolean,
    val position: Pair<Int, Int>,
    val isAtLatest: Boolean,
)

private val ChatFollowStateSaver = Saver<ChatFollowState, Boolean>(
    save = { it.isFollowingLatest },
    restore = { ChatFollowState(it) },
)

@Composable
internal fun HbTranscriptSectionHeader(section: HbChatSection, modifier: Modifier = Modifier) {
    Box(
        // The content mask also clears this lower gutter; hidden links must not receive its taps.
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } }.padding(bottom = HbTheme.spacing.m),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = HbTheme.dimensions.touchTarget)
                .hbSurface(HbTheme.colors.background, HbTheme.shapes.small)
                .semantics { heading() }
                .padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            HbText(section.title, style = HbTheme.typography.label)
        }
    }
}

private suspend fun LazyListState.moveToTimelineEnd(lastIndex: Int, isAnimated: Boolean) {
    if (lastIndex < 0) return
    if (isAnimated) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val finalItem = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    val remaining = (finalItem?.size ?: 0) + layoutInfo.afterContentPadding
    if (isAnimated) animateScrollBy(remaining.toFloat()) else scrollBy(remaining.toFloat())
}

/** Dates are small chronological markers, independent of the message and the pinned conversation header. */
@Composable
private fun HbTranscriptDateHeader(section: HbChatSection, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = HbTheme.spacing.l), contentAlignment = Alignment.Center) {
        HbText(
            section.title,
            Modifier.background(HbTheme.surfaces.header, HbTheme.shapes.small)
                .padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.xs).semantics { heading() },
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}
