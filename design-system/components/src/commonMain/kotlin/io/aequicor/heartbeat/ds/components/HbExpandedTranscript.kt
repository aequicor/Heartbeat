package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList

/** Sparse display intervals: unchanged messages never need a flattened copy on disclosure or streaming. */
@Immutable
internal data class HbExpandedTimelineSection(
    val source: HbTimelineSection,
    val tools: ImmutableList<HbExpandedTool>,
) {
    val itemCount: Int get() = source.entries.size + tools.sumOf { it.body.rows.size }
}

@Immutable
internal data class HbExpandedTool(val entryIndex: Int, val chunk: HbTranscriptChunk, val body: HbTranscriptBody.Tool)

internal fun expandedTranscriptSections(
    timeline: HbChatTimeline,
    expandedKeys: ImmutableSet<String>,
): ImmutableList<HbExpandedTimelineSection> = timeline.sections.map { section ->
    val tools = expandedKeys.mapNotNull { key ->
        section.toolEntries[key]?.let { index ->
            val chunk = section.entries[index]
            val body = chunk.body as HbTranscriptBody.Tool
            if (body.rows.isEmpty()) null else HbExpandedTool(index, chunk, body)
        }
    }.sortedBy { it.entryIndex }.toImmutableList()
    HbExpandedTimelineSection(section, tools)
}.toImmutableList()

internal fun LazyListScope.hbExpandedTimelineItems(
    section: HbExpandedTimelineSection,
    content: @Composable (HbTranscriptChunk) -> Unit,
) {
    var start = 0
    section.tools.forEach { tool ->
        baseItems(section.source, start, tool.entryIndex + 1, expandedLastIndex = tool.entryIndex, content = content)
        items(
            count = tool.body.rows.size,
            key = { tool.payloadChunk(it).key },
            contentType = { tool.body.rows[it].contentType },
        ) { index -> content(tool.payloadChunk(index)) }
        start = tool.entryIndex + 1
    }
    baseItems(section.source, start, section.source.entries.size, content = content)
}

private fun LazyListScope.baseItems(
    section: HbTimelineSection,
    start: Int,
    end: Int,
    expandedLastIndex: Int = -1,
    content: @Composable (HbTranscriptChunk) -> Unit,
) {
    if (end <= start) return
    items(
        count = end - start,
        key = { section.entries[start + it].key },
        contentType = { section.entries[start + it].contentType },
    ) { index ->
        val chunk = section.entries[start + index]
        content(if (start + index == expandedLastIndex && chunk.isLast) chunk.copy(isLast = false) else chunk)
    }
}

private fun HbExpandedTool.payloadChunk(index: Int): HbTranscriptChunk = HbTranscriptChunk(
    messageId = chunk.messageId,
    id = "tool-payload:${body.call.id.length}:${body.call.id}:${body.rows[index].id}",
    body = HbTranscriptBody.ToolPayload(body.rows[index], isFirst = index == 0, isLast = index == body.rows.lastIndex),
    isLast = chunk.isLast && index == body.rows.lastIndex,
)
