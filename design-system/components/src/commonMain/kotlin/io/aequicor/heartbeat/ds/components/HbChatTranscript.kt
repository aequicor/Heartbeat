package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch

private val log = Log.tag("DS/ChatTranscript")

/**
 * Virtualized chronological transcript, anchored to the bottom while following new content.
 * User scrolling pauses following; scrolling back to the bottom or the explicit action resumes it.
 * Stable message ids preserve item state during incremental text updates and history insertion.
 * [onToolAction] receives the pressed action of a tool call together with that call.
 */
@Composable
public fun HbChatTranscript(
    messages: ImmutableList<HbChatMessage>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    streamingLabel: String = "",
    jumpToLatestLabel: String = "",
    onJumpToLatest: () -> Unit = {},
    onToolAction: (HbToolCall, HbToolAction) -> Unit = { _, _ -> },
    messageContent: (@Composable (HbChatMessage) -> Unit)? = null,
) {
    var followState by remember(state) {
        mutableStateOf(ChatFollowState(isChatAtLatest(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)))
    }
    var isJumpingToLatest by remember(state) { mutableStateOf(false) }
    val isAtLatest by remember(state) {
        derivedStateOf { isChatAtLatest(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    val scope = rememberCoroutineScope()
    val isReducedMotion = HbTheme.motion.isReducedMotion
    val latest = messages.lastOrNull()

    LaunchedEffect(state) {
        var previousPosition = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        snapshotFlow {
            val position = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            Triple(state.isScrollInProgress, position, isAtLatest)
        }.collect { (isScrolling, position, isLatestVisible) ->
            // Scrollbar seeks scroll synchronously and never expose isScrollInProgress, so movement counts too.
            val hasMoved = position != previousPosition
            previousPosition = position
            followState = updateFollowState(followState, isScrolling || hasMoved, isJumpingToLatest, isLatestVisible)
        }
    }
    LaunchedEffect(state, messages.size, latest) {
        log.d { "transcript updated count=${messages.size} latestLength=${latest?.text?.length ?: 0}" }
        if (messages.isEmpty()) followState = ChatFollowState()
        if (messages.isNotEmpty() && followState.shouldScrollOnUpdate(state.isScrollInProgress)) {
            state.scrollToItem(0)
        }
    }

    Box(modifier = modifier) {
        HbLazyColumn(modifier = Modifier.fillMaxSize(), state = state, reverseLayout = true) {
            items(
                count = messages.size,
                key = { messages[messages.lastIndex - it].id },
                contentType = { messages[messages.lastIndex - it].kind },
            ) { index ->
                val message = messages[messages.lastIndex - index]
                HbChatMessageBubble(
                    message = message,
                    streamingLabel = streamingLabel,
                    onToolAction = onToolAction,
                    content = messageContent?.let { render -> { render(message) } },
                )
            }
        }
        if (!isAtLatest && jumpToLatestLabel.isNotBlank()) {
            HbButton(
                text = jumpToLatestLabel,
                onClick = {
                    log.i { "jump to latest requested" }
                    followState = followState.onJumpToLatest()
                    scope.launch {
                        isJumpingToLatest = true
                        try {
                            state.jumpToLatest(isReducedMotion)
                        } finally {
                            isJumpingToLatest = false
                            followState = followState.onUserScroll(
                                isChatAtLatest(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset),
                            )
                            log.d { "jump finished following=${followState.isFollowingLatest}" }
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

private suspend fun LazyListState.jumpToLatest(isReducedMotion: Boolean) {
    if (isReducedMotion) scrollToItem(0) else animateScrollToItem(0)
}

private fun updateFollowState(
    previous: ChatFollowState,
    isScrolling: Boolean,
    isJumpingToLatest: Boolean,
    isLatestVisible: Boolean,
): ChatFollowState {
    if (!isScrolling || isJumpingToLatest) return previous
    val updated = previous.onUserScroll(isLatestVisible)
    if (updated != previous) log.d { "transcript follow latest=${updated.isFollowingLatest}" }
    return updated
}
