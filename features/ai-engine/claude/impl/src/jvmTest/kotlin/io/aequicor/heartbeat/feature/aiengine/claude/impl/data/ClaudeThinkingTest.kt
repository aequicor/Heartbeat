package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClaudeThinkingTest {
    @Test
    fun `exposed thinking preserves order without leaking signatures or redacted blocks`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(
                """{"type":"assistant","session_id":"$id","message":{"content":[
                    {"type":"thinking","thinking":"Check the files","signature":"opaque"},
                    {"type":"text","text":"Answer"},
                    {"type":"redacted_thinking","data":"opaque"}
                ]}}
                """.trimIndent(),
            )
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        val items = session.features.available(SessionHistory).page().items
        val messages = items.filterIsInstance<SessionItem.Message>().filter { it.role == MessageRole.Assistant }
        assertEquals(
            listOf(listOf(ContentPart.Reasoning("Check the files")), listOf(ContentPart.Text("Answer"))),
            messages.map { it.parts },
        )
        assertIs<SessionItem.UnsupportedItem>(items.last())
        assertEquals(messages.first().info.turn, messages.last().info.turn)
        runtime.close()
    }

    @Test
    fun `thinking does not suppress the result text fallback`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(
                """{"type":"assistant","session_id":"$id","message":{"content":[
                    {"type":"thinking","thinking":"Check","signature":"opaque"}
                ]}}
                """.trimIndent(),
            )
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        val replies = session.features.available(SessionHistory).page().items.filterIsInstance<SessionItem.Message>()
            .filter { it.role == MessageRole.Assistant }
        assertEquals(2, replies.size)
        assertIs<ContentPart.Reasoning>(replies.first().parts.single())
        assertIs<ContentPart.Text>(replies.last().parts.single())
        runtime.close()
    }
}
