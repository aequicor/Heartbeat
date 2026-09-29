package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PiStoredTranscriptTest {
    @Test
    fun `empty session has no stored messages`() {
        assertEquals(emptyList(), storedBranch(stored("""{"leafId":null,"entries":[]}""")))
    }

    @Test
    fun `branch cut at a missing parent keeps its known tail`() {
        val branch = storedBranch(
            stored(
                """{"leafId":"b","entries":[
                {"type":"message","id":"a","parentId":"gone","message":{"role":"user","content":"A"}},
                {"type":"message","id":"b","parentId":"a","message":{"role":"assistant","content":"B"}}]}""",
            ),
        )
        assertEquals(listOf("A", "B"), branch.map { it.string("content") })
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
