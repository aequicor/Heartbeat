package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/** Caller-localized date or session group. Keep [id] stable while its title changes language. */
@Immutable
public data class HbChatSection(val id: String, val title: String, val isDate: Boolean = false) {
    init {
        require(id.isNotBlank()) { "A chat section needs a stable non-blank id." }
    }
}

/**
 * Prepared, immutable transcript. Build history once, then replace only the latest streaming message.
 * Host-owned entries after the history go through [appendTail] on a copy that is only rendered.
 * Persistent collections share unchanged history; rendering never validates or flattens the full list.
 */
@Immutable
public class HbChatTimeline private constructor(
    internal val sections: PersistentList<HbTimelineSection>,
    private val orderedMessages: PersistentList<HbChatMessage>,
    private val messagesById: PersistentMap<String, HbChatMessage>,
    internal val latestEntryStart: Int,
    public val itemCount: Int,
) {
    public val messages: ImmutableList<HbChatMessage> get() = orderedMessages
    public val messageCount: Int get() = orderedMessages.size
    public val latestMessage: HbChatMessage? get() = orderedMessages.lastOrNull()

    internal fun message(id: String): HbChatMessage = checkNotNull(messagesById[id])

    /** Appends one message, creating a new sticky section when [section] changes. */
    public fun append(section: HbChatSection, message: HbChatMessage): HbChatTimeline =
        append(section, message, transcriptChunks(message))

    /**
     * Appends host-owned [entries] that follow the history on every update, such as status cards.
     * Chunks of an entry that [previous] already ended with are reused, so unchanged entries are not prepared
     * again while the history before them streams, and changed ones keep their prepared tool rows.
     *
     * The result is a render snapshot: its [latestMessage] is the last entry, so keep the history timeline
     * separately, stream into it with [replaceLatest] or [append], and call this again on each update with the
     * previous snapshot as [previous]. Entry ids must not repeat ids of the history.
     */
    public fun appendTail(
        section: HbChatSection,
        entries: List<HbChatMessage>,
        previous: HbChatTimeline? = null,
    ): HbChatTimeline {
        if (entries.isEmpty()) return this
        val reusable = previous?.trailingChunks(entries.mapTo(HashSet()) { it.id }).orEmpty()
        return entries.fold(this) { timeline, entry ->
            val prepared = reusable[entry.id].orEmpty()
            val chunks = if (prepared.isNotEmpty() && previous?.messagesById?.get(entry.id) == entry) {
                prepared.toPersistentList()
            } else {
                transcriptChunks(entry, prepared)
            }
            timeline.append(section, entry, chunks)
        }
    }

    /** Chunks of the [ids] messages that end this timeline, in display order. */
    private fun trailingChunks(ids: Set<String>): Map<String, List<HbTranscriptChunk>> {
        val entries = sections.lastOrNull()?.entries ?: return emptyMap()
        val found = mutableMapOf<String, ArrayDeque<HbTranscriptChunk>>()
        for (index in entries.indices.reversed()) {
            val chunk = entries[index]
            if (chunk.messageId !in ids) break
            found.getOrPut(chunk.messageId) { ArrayDeque() }.addFirst(chunk)
        }
        return found
    }

    private fun append(
        section: HbChatSection,
        message: HbChatMessage,
        chunks: PersistentList<HbTranscriptChunk>,
    ): HbChatTimeline {
        require(message.id !in messagesById) { "Chat message ids must be unique: ${message.id}" }
        val previousSection = sections.lastOrNull()
        val isContinuingSection = previousSection?.section?.id == section.id
        val previousEntries = previousSection?.entries ?: persistentListOf()
        val start = if (isContinuingSection) previousEntries.size else 0
        val nextSections = if (isContinuingSection) {
            sections.replacingAt(
                sections.lastIndex,
                HbTimelineSection(
                    section,
                    previousEntries.addingAll(chunks),
                    updateToolEntries(previousSection.toolEntries, chunks, start),
                ),
            )
        } else {
            require(sections.none { it.section.id == section.id }) { "Chat sections must be contiguous." }
            sections.adding(HbTimelineSection(section, chunks, updateToolEntries(persistentMapOf(), chunks, start)))
        }
        return HbChatTimeline(
            nextSections,
            orderedMessages.adding(message),
            messagesById.putting(message.id, message),
            start,
            itemCount + chunks.size + if (isContinuingSection) 0 else 1,
        )
    }

    /** Replaces the streaming tail without inspecting or rebuilding preceding messages. */
    public fun replaceLatest(message: HbChatMessage): HbChatTimeline {
        require(latestMessage?.id == message.id) { "replaceLatest requires the current latest message id." }
        val currentSection = sections.last()
        val previousChunks = currentSection.entries.subList(latestEntryStart, currentSection.entries.size)
        val chunks = transcriptChunks(message, previousChunks).mapIndexed { index, chunk ->
            currentSection.entries.getOrNull(latestEntryStart + index)?.takeIf { it == chunk } ?: chunk
        }
        val updatedEntries = currentSection.entries.builder().apply {
            while (size > latestEntryStart) removeAt(lastIndex)
            addAll(chunks)
        }.build()
        return HbChatTimeline(
            sections.replacingAt(
                sections.lastIndex,
                currentSection.copy(
                    entries = updatedEntries,
                    toolEntries = updateToolEntries(
                        currentSection.toolEntries,
                        chunks,
                        latestEntryStart,
                        previousChunks,
                    ),
                ),
            ),
            orderedMessages.replacingAt(orderedMessages.lastIndex, message),
            messagesById.putting(message.id, message),
            latestEntryStart,
            itemCount - currentSection.entries.size + latestEntryStart + chunks.size,
        )
    }

    /** Prepends an older page. Existing lazy keys retain the reader's visible message and offset. */
    public fun prepend(section: HbChatSection, messages: ImmutableList<HbChatMessage>): HbChatTimeline {
        if (messages.isEmpty()) return this
        val prefix = from(section, messages)
        require(messages.none { it.id in messagesById }) { "Prepended message ids must be new." }
        if (sections.isEmpty()) return prefix
        val isJoiningFirstSection = sections.first().section.id == section.id
        val nextSections = if (isJoiningFirstSection) {
            val joined = prefix.sections.first().copy(
                entries = prefix.sections.first().entries.addingAll(sections.first().entries),
                toolEntries = prefix.sections.first().toolEntries.builder().apply {
                    sections.first().toolEntries.forEach { (key, index) ->
                        put(key, index + prefix.sections.first().entries.size)
                    }
                }.build(),
            )
            sections.replacingAt(0, joined)
        } else {
            require(sections.none { it.section.id == section.id }) { "Chat sections must be contiguous." }
            prefix.sections.addingAll(sections)
        }
        val addedEntries = prefix.sections.first().entries.size
        val latestStart = latestEntryStart + if (isJoiningFirstSection && sections.size == 1) addedEntries else 0
        return HbChatTimeline(
            nextSections,
            prefix.orderedMessages.addingAll(orderedMessages),
            prefix.messagesById.puttingAll(messagesById),
            latestStart,
            itemCount + prefix.itemCount - if (isJoiningFirstSection) 1 else 0,
        )
    }

    /** Empty state and initial batch preparation. Streaming callers should use [replaceLatest]. */
    public companion object {
        public val Empty: HbChatTimeline = HbChatTimeline(
            persistentListOf(),
            persistentListOf(),
            persistentMapOf(),
            0,
            0,
        )

        /** Prepares one initial section; repeated stream updates should use [replaceLatest]. */
        public fun from(section: HbChatSection, messages: ImmutableList<HbChatMessage>): HbChatTimeline =
            messages.fold(Empty) { timeline, message -> timeline.append(section, message) }
    }
}

@Immutable
internal data class HbTimelineSection(
    val section: HbChatSection,
    val entries: PersistentList<HbTranscriptChunk>,
    val toolEntries: PersistentMap<String, Int> = persistentMapOf(),
)

private fun updateToolEntries(
    previous: PersistentMap<String, Int>,
    chunks: List<HbTranscriptChunk>,
    start: Int,
    removedChunks: List<HbTranscriptChunk> = emptyList(),
): PersistentMap<String, Int> = previous.builder().apply {
    removedChunks.forEach { chunk ->
        if (chunk.body is HbTranscriptBody.Tool) remove(chunk.key)
    }
    chunks.forEachIndexed { index, chunk ->
        if (chunk.body is HbTranscriptBody.Tool) put(chunk.key, start + index)
    }
}.build()
