package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

/**
 * Explicitly preserves the reader when disclosure or a history prepend moves an item. LazyList only
 * re-finds the first visible key within its nearby key window (about 100 rows), so larger shifts would
 * otherwise keep the old numeric index and silently show different content.
 */
@Composable
internal fun PreserveDisclosureAnchor(
    state: LazyListState,
    sections: ImmutableList<HbExpandedTimelineSection>,
    expandedKeys: ImmutableSet<String>,
    showSectionHeaders: Boolean = true,
) {
    val previous = remember(state) { DisclosureProjection(sections, expandedKeys, showSectionHeaders) }
    SideEffect {
        val previousSections = previous.sections
        val hasPreviousSectionHeaders = previous.hasSectionHeaders
        val hasProjectionChanged = previousSections !== sections || previous.expandedKeys != expandedKeys ||
            hasPreviousSectionHeaders != showSectionHeaders
        previous.update(sections, expandedKeys, showSectionHeaders)
        if (hasProjectionChanged) {
            val anchor = previousSections.disclosureAnchor(state.firstVisibleItemIndex, hasPreviousSectionHeaders)
            val position = anchor?.let {
                sections.restoreDisclosureAnchor(it, state.firstVisibleItemScrollOffset, showSectionHeaders)
            }
            val isMoved = position != null &&
                (position.index != state.firstVisibleItemIndex || position.offset != state.firstVisibleItemScrollOffset)
            // Tail streaming keeps earlier rows in place, so only real shifts request a new position.
            if (position != null && isMoved) state.requestScrollToItem(position.index, position.offset)
        }
    }
}

private class DisclosureProjection(
    var sections: ImmutableList<HbExpandedTimelineSection>,
    var expandedKeys: ImmutableSet<String>,
    var hasSectionHeaders: Boolean,
) {
    fun update(
        sections: ImmutableList<HbExpandedTimelineSection>,
        expandedKeys: ImmutableSet<String>,
        showSectionHeaders: Boolean,
    ) {
        this.sections = sections
        this.expandedKeys = expandedKeys
        this.hasSectionHeaders = showSectionHeaders
    }
}

private data class DisclosureAnchor(
    val sectionId: String,
    val entryIndex: Int,
    val entryKey: String? = null,
    val payloadId: String? = null,
)

private data class DisclosurePosition(val index: Int, val offset: Int)

private fun ImmutableList<HbExpandedTimelineSection>.disclosureAnchor(
    index: Int,
    showSectionHeaders: Boolean,
): DisclosureAnchor? {
    var relative = index
    for (section in this) {
        val headerCount = if (showSectionHeaders && section.source.section.title.isNotBlank()) 1 else 0
        if (relative < section.itemCount + headerCount) {
            return if (headerCount == 1 && relative == 0) {
                DisclosureAnchor(section.source.section.id, entryIndex = -1)
            } else {
                section.disclosureAnchor(relative - headerCount)
            }
        }
        relative -= section.itemCount + headerCount
    }
    return null
}

private fun HbExpandedTimelineSection.disclosureAnchor(index: Int): DisclosureAnchor? {
    var insertedRows = 0
    for (tool in tools) {
        val headerIndex = tool.entryIndex + insertedRows
        if (index <= headerIndex) break
        val payloadIndex = index - headerIndex - 1
        if (payloadIndex < tool.body.rows.size) {
            return DisclosureAnchor(
                source.section.id,
                tool.entryIndex,
                tool.chunk.key,
                tool.body.rows[payloadIndex].id,
            )
        }
        insertedRows += tool.body.rows.size
    }
    val entryIndex = index - insertedRows
    return source.entries.getOrNull(entryIndex)?.let { entry ->
        DisclosureAnchor(source.section.id, entryIndex, entry.key)
    }
}

private fun ImmutableList<HbExpandedTimelineSection>.restoreDisclosureAnchor(
    anchor: DisclosureAnchor,
    offset: Int,
    showSectionHeaders: Boolean,
): DisclosurePosition? {
    var sectionStart = 0
    for (section in this) {
        val headerCount = if (showSectionHeaders && section.source.section.title.isNotBlank()) 1 else 0
        if (section.source.section.id == anchor.sectionId) {
            return section.restoreSectionAnchor(anchor, sectionStart, headerCount, offset)
        }
        sectionStart += section.itemCount + headerCount
    }
    return null
}

private fun HbExpandedTimelineSection.restoreSectionAnchor(
    anchor: DisclosureAnchor,
    sectionStart: Int,
    headerCount: Int,
    offset: Int,
): DisclosurePosition? = if (anchor.entryIndex < 0) {
    DisclosurePosition(sectionStart, if (headerCount == 1) offset else 0)
} else {
    restoreDisclosureAnchor(anchor, sectionStart + headerCount, offset)
}

private fun HbExpandedTimelineSection.restoreDisclosureAnchor(
    anchor: DisclosureAnchor,
    sectionStart: Int,
    offset: Int,
): DisclosurePosition? {
    val entryIndex = anchorEntryIndex(anchor)
    if (entryIndex < 0) return null
    var displayedIndex = sectionStart + entryIndex
    for (tool in tools) {
        if (tool.entryIndex >= entryIndex) break
        displayedIndex += tool.body.rows.size
    }
    if (anchor.payloadId == null) return DisclosurePosition(displayedIndex, offset)
    val tool = tools.firstOrNull { it.entryIndex == entryIndex }
    val rowIndex = tool?.body?.rows?.indexOfFirst { it.id == anchor.payloadId } ?: -1
    return if (rowIndex >= 0) {
        DisclosurePosition(displayedIndex + 1 + rowIndex, offset)
    } else {
        // Collapsing the currently read payload leaves its disclosure header as the nearest anchor.
        DisclosurePosition(displayedIndex, 0)
    }
}

private fun HbExpandedTimelineSection.anchorEntryIndex(anchor: DisclosureAnchor): Int {
    val entryKey = anchor.entryKey ?: return -1
    if (source.entries.getOrNull(anchor.entryIndex)?.key == entryKey) return anchor.entryIndex
    source.toolEntries[entryKey]?.let { return it }
    // Only used on a disclosure event combined with a prepend or message replacement.
    return source.entries.indexOfFirst { it.key == entryKey }
}
