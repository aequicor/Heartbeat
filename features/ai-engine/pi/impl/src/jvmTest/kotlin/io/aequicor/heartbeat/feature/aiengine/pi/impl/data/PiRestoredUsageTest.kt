package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PiRestoredUsageTest {
    @Test
    fun `reopening restores the last native request for every provider without generating text`() = runTest {
        for (provider in PiProvider.entries) {
            val fixture = fixture(
                this,
                transcript = PiTranscript(RuntimeRef, "native.jsonl"),
                targetModel = ModelId("${provider.id}/qwen3.8-max"),
            ) { _, connection ->
                connection.provider = provider.id
                connection.contextWindows = mapOf("${provider.id}/qwen3.8-max" to 1000000L)
                connection.entries = transcript(provider.id)
            }
            val usage = fixture.session.contextUsage.state.value
            assertEquals(4096L, usage?.usedTokens, provider.id)
            assertEquals(1000000L, usage?.capacityTokens, provider.id)
            assertEquals(Instant.fromEpochMilliseconds(1000), usage?.observation?.checkedAt)
            assertEquals(true, usage?.observation?.isStale)
            assertFalse("prompt" in fixture.connection.commands)
            fixture.session.shutdown()
        }
    }

    @Test
    fun `restored compatible usage retains unknown capacity and updates on the next response`() = runTest {
        val provider = PiProvider.OpenAiCompatible.id
        val fixture = fixture(
            this,
            transcript = PiTranscript(RuntimeRef, "native.jsonl"),
            targetModel = ModelId("$provider/qwen3.8-max"),
        ) { _, connection ->
            connection.provider = provider
            connection.entries = transcript(provider)
        }
        assertEquals(4096L, fixture.session.contextUsage.state.value?.usedTokens)
        assertNull(fixture.session.contextUsage.state.value?.capacityTokens)
        fixture.connection.event(
            record(
                """{"type":"message_end","message":{"role":"assistant","provider":"$provider",
                "model":"qwen3.8-max","stopReason":"stop","usage":{"totalTokens":8192}}}""",
            ),
        )
        assertEquals(8192L, fixture.session.contextUsage.state.value?.usedTokens)
        assertEquals(false, fixture.session.contextUsage.state.value?.observation?.isStale)
        fixture.session.shutdown()
    }

    @Test
    fun `recovery reads the replacement process branch and rejects late old process events`() = runTest {
        val fixture = fixture(this) { generation, connection ->
            connection.entries = transcript("anthropic", model = "test", tokens = if (generation == 0) 4096 else 8192)
        }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.session.synchronize()
        assertEquals(8192L, fixture.session.contextUsage.state.value?.usedTokens)
        fixture.connection.event(
            record(
                """{"type":"message_end","message":{"role":"assistant","provider":"anthropic",
                "model":"test","stopReason":"stop","usage":{"totalTokens":999}}}""",
            ),
        )
        assertEquals(8192L, fixture.session.contextUsage.state.value?.usedTokens)
        assertTrue("get_entries" in fixture.connections.last().commands)
        fixture.session.shutdown()
    }

    @Test
    fun `disabled telemetry and a different selected model do not restore a measurement`() = runTest {
        for (isEnabled in listOf(false, true)) {
            val fixture = fixture(
                this,
                transcript = PiTranscript(RuntimeRef, "native.jsonl"),
                isUsageEnabled = isEnabled,
            ) { _, connection ->
                connection.entries = transcript("anthropic", model = if (isEnabled) "other" else "test")
            }
            assertNull(fixture.session.contextUsage.state.value)
            fixture.session.shutdown()
        }
    }

    @Test
    fun `unavailable recovered telemetry never prevents continuation`() = runTest {
        val fixture = fixture(this) { generation, connection ->
            if (generation > 0) connection.entries = """{"entries":[]}"""
        }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.session.synchronize()
        assertNull(fixture.session.contextUsage.state.value)
        assertTrue(fixture.connections.last().isOpen)
        fixture.connections.last().promptAck.complete(record("{}"))
        fixture.session.send(prompt("after-telemetry-failure"))
        assertEquals("prompt", fixture.connections.last().commands.last())
        fixture.session.shutdown()
    }

    private fun transcript(provider: String, model: String = "qwen3.8-max", tokens: Int = 4096) =
        """{"leafId":"reply","entries":[{"type":"message","id":"reply","parentId":null,
        "message":{"role":"assistant","provider":"$provider","model":"$model","timestamp":1000,
        "content":[],"stopReason":"stop","usage":{"totalTokens":$tokens}}}]}"""
}
