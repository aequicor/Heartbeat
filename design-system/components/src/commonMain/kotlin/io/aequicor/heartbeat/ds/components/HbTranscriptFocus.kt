package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.collections.immutable.ImmutableList

/**
 * One host request to reveal [messageId], or its embedded content when [isEmbedded] is true.
 * A new [sequence] repeats a request for the same message; missing targets do not move the reader.
 */
@Immutable
public data class HbTranscriptFocus(val messageId: String, val sequence: Long, val isEmbedded: Boolean = false)

/** An explicit reveal suspends tail following before moving to the host's target. */
@Composable
internal fun TranscriptFocusEffect(
    state: LazyListState,
    sections: ImmutableList<HbExpandedTimelineSection>,
    request: HbTranscriptFocus?,
    showSectionHeaders: Boolean,
    onReveal: () -> Unit,
    onFocus: (Long) -> Unit,
) {
    val latestOnFocus by rememberUpdatedState(onFocus)
    val latestOnReveal by rememberUpdatedState(onReveal)
    LaunchedEffect(state, request) {
        if (request == null) return@LaunchedEffect
        val index = sections.focusIndex(request, showSectionHeaders)
        if (index != null) {
            latestOnReveal()
            state.scrollToItem(index)
        }
        latestOnFocus(request.sequence)
    }
}

/** Resolves a message against displayed rows, including sticky headings and disclosed tool payloads. */
internal fun ImmutableList<HbExpandedTimelineSection>.focusIndex(
    request: HbTranscriptFocus,
    showSectionHeaders: Boolean,
): Int? {
    var start = 0
    for (section in this) {
        val header = if (showSectionHeaders && section.source.section.title.isNotBlank()) 1 else 0
        val entry = section.source.entries.indexOfFirst {
            it.messageId == request.messageId && (!request.isEmbedded || it.body == HbTranscriptBody.Embedded)
        }
        if (entry >= 0) {
            return start + header + entry + section.tools.filter { it.entryIndex < entry }.sumOf { it.body.rows.size }
        }
        start += header + section.itemCount
    }
    return null
}
