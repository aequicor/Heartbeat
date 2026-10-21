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
import kotlin.test.assertNull
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

    @Test
    fun `only the current branch after its latest context replacement can supply usage`() {
        for (replacement in listOf("compaction", "branch_summary", "context_edit", "model_change")) {
            val old = """{"type":"message","id":"old","parentId":null,
                "message":{"role":"assistant","content":"Old"}}"""
            val boundary = """{"type":"$replacement","id":"boundary","parentId":"old"}"""
            val abandoned = """{"type":"message","id":"abandoned","parentId":"old",
                "message":{"role":"assistant","content":"Abandoned"}}"""
            val latest = """{"type":"message","id":"latest","parentId":"boundary",
                "message":{"role":"assistant","content":"Current"}}"""
            assertNull(
                storedBranch(
                    stored("""{"leafId":"boundary","entries":[$old,$boundary,$abandoned]}"""),
                ).contextMessage,
                replacement,
            )
            assertEquals(
                "Current",
                storedBranch(
                    stored("""{"leafId":"latest","entries":[$old,$boundary,$latest,$abandoned]}"""),
                ).contextMessage?.string("content"),
                replacement,
            )
        }
    }

    @Test
    fun `same model selection during reattachment retains usage but switching away never revives it`() {
        val reply = """{"type":"message","id":"reply","parentId":null,
            "message":{"role":"assistant","provider":"openai-compatible","model":"qwen3.8-max"}}"""
        val same = """{"type":"model_change","id":"same","parentId":"reply",
            "provider":"openai-compatible","modelId":"qwen3.8-max"}"""
        val away = """{"type":"model_change","id":"away","parentId":"same",
            "provider":"openai-compatible","modelId":"other"}"""
        val back = """{"type":"model_change","id":"back","parentId":"away",
            "provider":"openai-compatible","modelId":"qwen3.8-max"}"""
        assertEquals(
            "qwen3.8-max",
            storedBranch(stored("""{"leafId":"same","entries":[$reply,$same]}""")).contextMessage?.string("model"),
        )
        assertNull(
            storedBranch(stored("""{"leafId":"back","entries":[$reply,$same,$away,$back]}""")).contextMessage,
        )
    }

    private fun stored(json: String) = Json.parseToJsonElement(json).jsonObject
}
