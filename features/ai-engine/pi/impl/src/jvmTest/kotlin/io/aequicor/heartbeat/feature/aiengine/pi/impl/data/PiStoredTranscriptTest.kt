package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiStoredTranscriptTest {
    @Test
    fun `empty session has no stored messages`() {
        val branch = storedBranch(stored("""{"leafId":null,"entries":[]}"""))
        assertEquals(emptyList(), branch.messages)
        assertTrue(branch.isComplete)
    }

    @Test
    fun `branch rooted at the first entry is complete`() {
        val branch = storedBranch(
            stored(
                """{"leafId":"b","entries":[
                {"type":"message","id":"a","parentId":null,"message":{"role":"user","content":"A"}},
                {"type":"compaction","id":"c","parentId":"a","summary":"S"},
                {"type":"message","id":"b","parentId":"c","message":{"role":"assistant","content":"B"}}]}""",
            ),
        )
        assertEquals(listOf("A", "B"), branch.messages.map { it.string("content") })
        assertTrue(branch.isComplete)
    }

    @Test
    fun `branch cut at a missing parent keeps its known tail and is incomplete`() {
        val branch = storedBranch(
            stored(
                """{"leafId":"b","entries":[
                {"type":"message","id":"a","parentId":"gone","message":{"role":"user","content":"A"}},
                {"type":"message","id":"b","parentId":"a","message":{"role":"assistant","content":"B"}}]}""",
            ),
        )
        assertEquals(listOf("A", "B"), branch.messages.map { it.string("content") })
        assertFalse(branch.isComplete)
    }

    @Test
    fun `malformed entries are a protocol violation`() {
        val entry = """{"type":"message","id":"a","parentId":null,"message":{"role":"user","content":"A"}}"""
        listOf(
            """{"leafId":"a"}""",
            """{"entries":[$entry]}""",
            """{"leafId":null,"entries":[$entry]}""",
            """{"leafId":"unknown","entries":[$entry]}""",
        ).forEach { json ->
            val failure = assertFailsWith<EngineException>(json) { storedBranch(stored(json)) }.failure
            assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure)
        }
    }

    private fun stored(json: String) = Json.parseToJsonElement(json).jsonObject
}
