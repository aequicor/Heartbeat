package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * conversation is opened and clears them from the record afterwards, so an interrupted move leaves the
 * conversation readable from where it was.
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

    /** Items of [chatId] in display order, moved out of the chat record when they are still there. */
    suspend fun read(chatId: String): List<SessionItem> {
        val stored = dao.items(chatId).mapNotNull { it.item() }
        if (stored.isNotEmpty()) return stored
        val carried = legacy(chatId)
        if (carried.isEmpty()) return emptyList()
        replace(chatId, carried)
        contracted(chatId)
        log.i { "Stored conversation transcript moved to the feature database: ${carried.size} items" }
        return carried
    }

    /** Items of [chatId] and their changes, in display order; [read] moves a stored transcript here first. */
    fun observe(chatId: String): Flow<List<SessionItem>> =
        dao.observe(chatId).map { rows -> rows.mapNotNull { it.item() } }

    /**
     * Stores [items] as the whole transcript of [chatId]: rows of new, reordered and revised items are written,
     * rows of items [items] no longer holds are deleted, and unchanged rows are left alone.
     */
    suspend fun replace(chatId: String, items: List<SessionItem>) {
        val stored = dao.positions(chatId).associateBy { it.itemId }
        val kept = items.mapTo(mutableSetOf()) { it.info.id.value }
        val rows = items.mapIndexedNotNull { ordinal, item -> row(chatId, ordinal, item, stored[item.info.id.value]) }
        val removed = stored.keys.filterNot { it in kept }
        if (rows.isEmpty() && removed.isEmpty()) return
        dao.apply(rows, chatId, removed)
        log.d { "Transcript stored: ${rows.size} of ${items.size} items written, ${removed.size} removed" }
    }

    /** Row of [item] at [ordinal], or null when [previous] already holds this revision in this place. */
    private fun row(
        chatId: String,
        ordinal: Int,
        item: SessionItem,
        previous: StudioTranscriptPosition?,
    ): StudioTranscriptEntity? {
        val id = item.info.id.value
        if (previous != null && previous.ordinal == ordinal && previous.revision >= item.info.revision) return null
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
