package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Transcripts of studio conversations, one database row per native item.
 *
 * A write stores the rows whose item, order or revision changed, so a streamed revision costs its own item and not
 * every conversation of the profile; a read decodes one item at a time. An item that cannot be decoded is skipped
 * and reported without its content: one corrupt row must not hide the rest of the conversation.
 *
 * A version before this database kept the items inside the chat record. [read] moves them here the first time a
 * conversation is opened and clears them from the record afterwards. Existing rows remain authoritative when
 * an interrupted move left the old carrier behind, and clearing that carrier is retried on the next read.
 * The caller serializes reads and writes, including the migration callbacks.
 *
 * @param dao transcript rows.
 * @param legacy items still stored inside the chat record of a conversation, empty once it has been moved.
 * @param contracted drops the moved items from the chat record; called only after the rows are stored.
 */
internal class StudioTranscripts(
    private val dao: StudioTranscriptDao,
    private val legacy: suspend (String) -> List<SessionItem>,
    private val contracted: suspend (String) -> Unit,
) {
    private val log = Log.tag("StudioTranscripts")
    private val checkedLegacy = mutableSetOf<String>()

    /** Items of [chatId] in display order, moved out of the chat record when they are still there. */
    suspend fun read(chatId: String): List<SessionItem> {
        log.v { "Read stored transcript" }
        val rows = dao.items(chatId)
        val stored = rows.mapNotNull { it.item() }
        if (chatId in checkedLegacy) return stored
        val carried = legacy(chatId)
        val result = if (rows.isEmpty() && carried.isNotEmpty()) {
            replace(chatId, carried, previousItems = stored)
            carried
        } else {
            stored
        }
        if (carried.isNotEmpty()) {
            contracted(chatId)
            log.i { "Stored conversation transcript carrier cleared: ${carried.size} items" }
        }
        checkedLegacy += chatId
        return result
    }

    /** Items of [chatId] and their changes, in display order; [read] moves a stored transcript here first. */
    fun observe(chatId: String): Flow<List<SessionItem>> = dao.observe(chatId)
        .onStart { log.v { "Observe stored transcript" } }
        .map { rows -> rows.mapNotNull { it.item() } }

    /**
     * Stores [items] as the whole transcript of [chatId]: rows of new, reordered and revised items are written,
     * rows of items [items] no longer holds are deleted, and unchanged rows are left alone.
     *
     * Native revisions may restart when an adapter reopens, so unchanged means equal item content and display
     * order. [previousItems] reuses a snapshot read under the caller's lock to avoid decoding those rows again.
     */
    suspend fun replace(chatId: String, items: List<SessionItem>, previousItems: List<SessionItem>? = null) {
        val previous = (previousItems ?: dao.items(chatId).mapNotNull { it.item() })
            .associateBy { it.info.id.value }
        val stored = dao.positions(chatId).associateBy { it.itemId }
        val kept = items.mapTo(mutableSetOf()) { it.info.id.value }
        val rows = items.mapIndexedNotNull { ordinal, item ->
            row(chatId, ordinal, item, stored[item.info.id.value], previous[item.info.id.value])
        }
        val removed = stored.keys.filterNot { it in kept }
        if (rows.isNotEmpty() || removed.isNotEmpty()) dao.apply(rows, chatId, removed)
        log.v { "Transcript stored: ${rows.size} of ${items.size} items written, ${removed.size} removed" }
    }

    /** Row of [item] at [ordinal], or null when the stored row already holds this item in this place. */
    private fun row(
        chatId: String,
        ordinal: Int,
        item: SessionItem,
        previous: StudioTranscriptPosition?,
        previousItem: SessionItem?,
    ): StudioTranscriptEntity? {
        val id = item.info.id.value
        if (previous != null && previous.ordinal == ordinal && previousItem == item) return null
        return StudioTranscriptEntity(chatId, id, ordinal, item.info.revision, json.encodeToString(serializer, item))
    }

    private fun StudioTranscriptEntity.item(): SessionItem? = try {
        json.decodeFromString(serializer, payload)
    } catch (e: SerializationException) {
        log.w(e.withoutStoredItem()) { "Stored transcript item is not decodable and is skipped" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e.withoutStoredItem()) { "Stored transcript item does not match its schema and is skipped" }
        null
    }

    /** Decoder messages and causes quote the decoded text, which is the conversation of the user. */
    private fun Throwable.withoutStoredItem(): Throwable =
        SerializationException("Stored transcript item decoding failed (${this::class.simpleName ?: "unknown error"})")

    private companion object {
        val json = Json
        val serializer = SessionItem.serializer()
    }
}
