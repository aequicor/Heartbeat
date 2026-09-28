package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioProjectPersistenceTest {
    @Test
    fun `pre-project chats load without changing their identity or history`() {
        val record = Json.decodeFromString<StudioChatRecord>(
            """{"id":"old","title":"Saved chat","updatedAt":"2026-09-28T00:00:00Z"}""",
        )
        assertEquals("old", record.id)
        assertNull(record.projectId)
    }

    @Test
    fun `project identity is retained when the chat is restored`() {
        val record = StudioChatRecord("chat", "Work", Instant.fromEpochSeconds(10), projectId = "opaque-project")
        val restored = Json.decodeFromString<StudioChatRecord>(
            Json.encodeToString(StudioChatRecord.serializer(), record),
        )
        assertEquals(record, restored)
    }
}
