@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ClaudeUsageTest {
    @Test
    fun `context includes caches and ignores accumulated result and subagent usage`() {
        val usage = ClaudeContextUsage()
        usage.receive(
            parseClaudeObject(
                """
                {"type": "assistant", "message": {"model": "actual", "usage": {"input_tokens": 10,
                "output_tokens": 5, "cache_read_input_tokens": 30, "cache_creation_input_tokens": 20}}}
                """,
            ),
        )
        assertNull(usage.state.value)
        usage.receive(
            parseClaudeObject(
                """
                {"type": "result", "usage": {"input_tokens": 50000}, "modelUsage": {"other":
                {"contextWindow": 10}, "actual": {"inputTokens": 50000, "contextWindow": 100}}}
                """,
            ),
        )
        assertEquals(65L, usage.state.value?.usedTokens)
        assertEquals(100L, usage.state.value?.capacityTokens)
        usage.receive(
            parseClaudeObject(
                """
                {"type": "assistant", "parent_tool_use_id": "tool", "message": {"model": "other",
                "usage": {"input_tokens": 999, "output_tokens": 1}}}
                """,
            ),
        )
        assertEquals(65L, usage.state.value?.usedTokens)
        usage.receive(
            parseClaudeObject(
                """
                {"type": "system", "subtype": "compact_boundary"}
                """,
            ),
        )
        assertNull(usage.state.value)
        usage.receive(
            parseClaudeObject(
                """
                {"type": "result", "modelUsage": {"actual": {"contextWindow": 100}}}
                """,
            ),
        )
        assertNull(usage.state.value)
    }

    @Test
    fun `unknown actual model or malformed usage never reuses another model capacity`() {
        val usage = ClaudeContextUsage()
        usage.receive(
            parseClaudeObject(
                """
                {"type": "assistant", "message": {"model": "a", "usage": {"input_tokens": 10,
                "output_tokens": 5}}}
                """,
            ),
        )
        usage.receive(
            parseClaudeObject(
                """
                {"type": "result", "modelUsage": {"a": {"contextWindow": 100}}}
                """,
            ),
        )
        usage.receive(
            parseClaudeObject(
                """
                {"type": "assistant", "message": {"model": "b", "usage": {"input_tokens": 10,
                "output_tokens": 5}}}
                """,
            ),
        )
        assertNull(usage.state.value)
        usage.receive(
            parseClaudeObject(
                """
                {"type": "result", "modelUsage": {"a": {"contextWindow": 100}}}
                """,
            ),
        )
        assertNull(usage.state.value)
        usage.receive(
            parseClaudeObject(
                """
                {"type": "assistant", "message": {"model": "a", "usage": {"input_tokens": 10}}}
                """,
            ),
        )
        usage.receive(
            parseClaudeObject(
                """
                {"type": "result", "modelUsage": {"a": {"contextWindow": 100}}}
                """,
            ),
        )
        assertNull(usage.state.value)
    }

    @Test
    fun `quota events normalize fractions and preserve independent windows`() = runTest {
        val provider = ClaudeProviderUsage { true }
        provider.receive(
            parseClaudeObject(
                """
                {"type": "rate_limit_event", "rate_limit_info": {"rateLimitType": "five_hour",
                "utilization": 0.25, "resetsAt": 100}}
                """,
            ),
        )
        provider.receive(
            parseClaudeObject(
                """
                {"type": "rate_limit_event", "rate_limit_info": {"rateLimitType": "seven_day",
                "utilization": 0}}
                """,
            ),
        )
        provider.receive(
            parseClaudeObject(
                """
                {"type": "rate_limit_event", "rate_limit_info": {"rateLimitType": "overage", "status":
                "rejected"}}
                """,
            ),
        )
        val snapshot = provider.refresh()
        assertEquals(2, snapshot.windows.size)
        assertEquals(25.0, snapshot.windows.first().usedPercent)
        assertEquals(Instant.fromEpochSeconds(100), snapshot.windows.first().resetsAt)
        assertEquals(0.0, snapshot.windows.last().usedPercent)
    }

    @Test
    fun `runtime publishes native telemetry without additional generation`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.first { it.startsWith("--session-id=") }.substringAfter('=')
            line(initFrame(id))
            line(
                """
                {"type": "assistant", "session_id": "$id", "message": {"model": "actual", "content":
                [{"type": "text", "text": "ok"}], "usage": {"input_tokens": 40, "output_tokens": 10}}}
                """,
            )
            line(
                """
                {"type": "rate_limit_event", "session_id": "$id", "rate_limit_info": {"rateLimitType":
                "five_hour", "utilization": 0.5}}
                """,
            )
            line(
                """
                {"type": "result", "session_id": "$id", "subtype": "success", "is_error": false,
                "modelUsage": {"actual": {"contextWindow": 100}}}
                """,
            )
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(prompt())
        runCurrent()
        assertEquals(50L, session.features.available(SessionContextUsage).state.value?.usedTokens)
        val provider = runtime.features.available(ReportsProviderUsage)
        assertEquals(50.0, provider.refresh().windows.single().usedPercent)
        assertEquals(1, fixture.transport.calls.count { "--print" in it })
        fixture.toggles.usageEnabled = false
        assertEquals(emptyList(), provider.refresh().windows)
        runtime.close()
    }

    @Test
    fun `runtime quota refresh preserves snapshot on network failure and clears on account change`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.first { it.startsWith("--session-id=") }.substringAfter('=')
            line(initFrame(id))
            line(assistantFrame(id))
            line(
                """{"type":"rate_limit_event","session_id":"$id","rate_limit_info":
                {"rateLimitType":"five_hour","utilization":0.5,"resetsAt":100}}""",
            )
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(prompt())
        runCurrent()
        val provider = runtime.features.available(ReportsProviderUsage)
        val previous = provider.refresh()
        val failure = EngineException(EngineFailure.Transport(TransportFailureReason.NetworkUnavailable))
        fixture.transport.beforeRun = { throw failure }
        assertEquals(failure, assertFailsWith<EngineException> { provider.refresh() })
        assertEquals(previous.windows, provider.state.value.windows)
        assertEquals(previous.observation.checkedAt, provider.state.value.observation.checkedAt)
        assertTrue(provider.state.value.observation.isStale)
        fixture.transport.beforeRun = {}
        fixture.transport.account = "other@example.test"
        assertIs<EngineFailure.Authentication>(assertFailsWith<EngineException> { provider.refresh() }.failure)
        assertEquals(emptyList(), provider.state.value.windows)
        assertEquals(1, fixture.transport.calls.count { "--print" in it })
        runtime.close()
    }
}
