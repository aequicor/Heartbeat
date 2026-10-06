package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioGraphRecoveryJournalTest {
    @Test
    fun `second restart before observer journaling retains the native receipt`() {
        val persisted = Json.encodeToString(
            mapOf("native-attempt" to GraphChatAttempt("helper", checkpoint = "native-receipt")),
        )
        val restored = Json.decodeFromString<Map<String, GraphChatAttempt>>(persisted)

        assertEquals("native-receipt", restored.recoveryAttempt("helper", "native-attempt")?.checkpoint)
    }

    @Test
    fun `second restart returns completed native result without repeating the assignment`() {
        val result = GraphTaskResult(GraphTaskPhase.Succeeded, "Changes committed")
        val persisted = Json.encodeToString(
            mapOf("native-attempt" to GraphChatAttempt("helper", result, "native-receipt")),
        )
        val restored = Json.decodeFromString<Map<String, GraphChatAttempt>>(persisted)

        assertEquals(result, restored.recoveryAttempt("helper", "native-attempt")?.result)
    }

    @Test
    fun `missing observer falls back only within its helper and to the latest journaled attempt`() {
        val journal = linkedMapOf(
            "old" to GraphChatAttempt("helper", checkpoint = "old-receipt", recoveryRoot = "root"),
            "new" to GraphChatAttempt("helper", checkpoint = "new-receipt", recoveryRoot = "root"),
            "foreign" to GraphChatAttempt("another-helper", checkpoint = "foreign-receipt", recoveryRoot = "root"),
        )

        assertEquals("new-receipt", journal.recoveryAttempt("helper", "root")?.checkpoint)
        assertNull(journal.recoveryAttempt("unknown-helper", "missing"))
        assertEquals(setOf("old", "new"), journal.recoveryLineage("helper", "root").keys)
    }

    @Test
    fun `explicit retry cannot reuse a completed earlier attempt`() {
        val journal = mapOf(
            "completed" to GraphChatAttempt("helper", GraphTaskResult(GraphTaskPhase.Succeeded, "old result")),
        )

        assertNull(journal.recoveryAttempt("helper", previousExecution = null))
        assertNull(journal.recoveryAttempt("helper", previousExecution = "retry-before-journaling"))
        assertEquals(emptySet(), journal.recoveryLineage("helper", "retry-before-journaling").keys)
    }

    @Test
    fun `journaled attempt without receipt does not resurrect older completed work`() {
        val journal = linkedMapOf(
            "completed" to GraphChatAttempt("helper", GraphTaskResult(GraphTaskPhase.Succeeded, "old result")),
            "new" to GraphChatAttempt("helper", recoveryRoot = "completed"),
        )

        assertEquals(
            GraphChatAttempt("helper", recoveryRoot = "completed"),
            journal.recoveryAttempt("helper", "completed"),
        )
    }
}
