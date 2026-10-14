package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.NoStudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioCarriedHarnessesTest {
    private val session = SessionRef(EngineId("codex"), SessionSourceId("profile"), "replacement")

    @Test
    fun `carried selections survive restart and failed attachment until confirmed before a later prompt`() = runTest {
        var record = StudioChatRecord(
            "chat",
            "Chat",
            Instant.DISTANT_PAST,
            ref = session,
            pendingHarnesses = setOf("restricted"),
        )
        var attempts = 0
        val attachments = object : StudioHarnessAttach by NoStudioHarnessAttach {
            override suspend fun restore(harnesses: Set<String>, session: SessionRef) {
                assertEquals(setOf("restricted"), harnesses)
                attempts++
                if (attempts == 1) error("Attachment not confirmed")
            }
        }
        assertFailsWith<IllegalStateException> {
            record.restoreCarriedHarnesses(session, attachments) { record = record.copy(pendingHarnesses = null) }
        }
        record = Json.decodeFromString(
            StudioChatRecord.serializer(),
            Json.encodeToString(StudioChatRecord.serializer(), record),
        )
        assertEquals(
            mapOf("external_browser" to setOf("browser"), "harness" to setOf("restricted")),
            record.withCarriedHarnesses(mapOf("external_browser" to setOf("browser"))),
        )

        record.restoreCarriedHarnesses(session, attachments) { record = record.copy(pendingHarnesses = null) }
        assertNull(record.pendingHarnesses)
        record.restoreCarriedHarnesses(session, attachments) { error("Already restored") }
        assertEquals(2, attempts)
    }
}
