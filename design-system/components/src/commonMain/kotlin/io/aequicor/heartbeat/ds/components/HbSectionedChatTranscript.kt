package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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

/**
 * Chronological, sectioned chat with pinned headers and individually virtualized message chunks.
 * Prepare [timeline] outside composition. Streaming only replaces the tail; history retains stable keys.
 * [messageAppearance] applies presentation preferences only to visible rows without mapping the history.
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
) {
    val expandedKeys = toolExpansionState.expandedKeys
    val sections = remember(timeline, expandedKeys) { expandedTranscriptSections(timeline, expandedKeys) }
    val displayedItemCount = sections.sumOf { it.itemCount + 1 }
    PreserveDisclosureAnchor(state, sections, expandedKeys)
    var followState by remember(state) {
        mutableStateOf(
            ChatFollowState(
                (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) ||
                    state.firstVisibleItemIndex >= displayedItemCount - 1,
            ),
        )
    }
    var isFollowingScroll by remember(state) { mutableStateOf(false) }
    val isAtLatest by remember(state) { derivedStateOf { !state.canScrollForward } }
    val scope = rememberCoroutineScope()
    val isReducedMotion = HbTheme.motion.isReducedMotion
    val latestItemCount by rememberUpdatedState(displayedItemCount)

    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to isAtLatest }.collect { (isScrolling, isLatestVisible) ->
            followState = updateTimelineFollowState(followState, isScrolling, isFollowingScroll, isLatestVisible)
        }
    }
    LaunchedEffect(state, timeline.latestMessage, timeline.itemCount) {
        timelineLog.d { "timeline updated messages=${timeline.messageCount} rows=${timeline.itemCount}" }
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
                contentPadding = PaddingValues(HbTheme.spacing.l),
                showScrollbar = false,
            ) {
                sections.forEach { section ->
                    hbStickyHeader(key = "section:${section.source.section.id}") {
                        headerContent("section:${section.source.section.id}")
                    }
                    hbExpandedTimelineItems(section) { chunk ->
                        val message = timeline.message(chunk.messageId)
                        TimelineMessageChunk(
                            chunk = chunk,
                            message = message,
                            streamingLabel = streamingLabel,
                            toolLabels = toolLabels,
                            onLinkClick = onLinkClick,
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(HbTheme.spacing.m),
                style = HbButtonStyle.Secondary,
            )
        }
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
    modifier: Modifier = Modifier,
    appearance: HbMessageAppearance? = null,
    isLatestMessage: Boolean = false,
    isToolExpanded: Boolean = false,
    onToolExpandedChange: (Boolean) -> Unit = {},
) {
    HbTranscriptChunkContent(
        chunk = chunk,
        modifier = modifier.padding(
            bottom = if (chunk.isLast && !isLatestMessage) HbTheme.spacing.l else HbTheme.elevation.none,
        ),
        message = message.copy(
            text = "",
            toolCalls = persistentListOf(),
            appearance = appearance ?: message.appearance,
        ),
        streamingLabel = streamingLabel,
        toolLabels = toolLabels,
        onLinkClick = onLinkClick,
        isToolExpanded = isToolExpanded,
        onToolExpandedChange = onToolExpandedChange,
    )
}

private fun updateTimelineFollowState(
    previous: ChatFollowState,
    isScrolling: Boolean,
    isFollowingScroll: Boolean,
    isAtLatest: Boolean,
): ChatFollowState {
    if (!isScrolling || isFollowingScroll) return previous
    val next = previous.onUserScroll(isAtLatest)
    if (next != previous) timelineLog.d { "follow latest=${next.isFollowingLatest}" }
    return next
}

@Composable
internal fun HbTranscriptSectionHeader(section: HbChatSection, modifier: Modifier = Modifier) {
    HbGlassPanel(
        // The content mask also clears this lower gutter; hidden links must not receive its taps.
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } }.padding(bottom = HbTheme.spacing.m),
        shape = HbTheme.shapes.small,
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
