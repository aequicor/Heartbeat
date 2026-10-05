package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory rows shared by successive viewer instances in persistence tests. */
internal class MemoryTranscriptDao : StudioTranscriptDao {
    private val rows = MutableStateFlow<List<StudioTranscriptEntity>>(emptyList())

    override fun observe(chatId: String) = rows.map { it.forChat(chatId) }

    override suspend fun items(chatId: String) = rows.value.forChat(chatId)

    override suspend fun positions(chatId: String) =
        items(chatId).map { StudioTranscriptPosition(it.itemId, it.ordinal, it.revision) }

    override suspend fun upsert(rows: List<StudioTranscriptEntity>) {
        val keys = rows.mapTo(mutableSetOf()) { it.chatId to it.itemId }
        this.rows.update { stored -> stored.filterNot { it.chatId to it.itemId in keys } + rows }
    }

    override suspend fun delete(chatId: String, itemIds: List<String>) {
        rows.update { stored -> stored.filterNot { it.chatId == chatId && it.itemId in itemIds } }
    }

    private fun List<StudioTranscriptEntity>.forChat(chatId: String) =
        filter { it.chatId == chatId }.sortedBy { it.ordinal }
}

internal fun MemoryTranscriptDao.transcripts() =
    lazy { StudioTranscripts(this, legacy = { emptyList() }, contracted = {}) }
