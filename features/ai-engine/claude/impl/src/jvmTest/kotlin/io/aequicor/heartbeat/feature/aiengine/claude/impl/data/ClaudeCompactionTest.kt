package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClaudeCompactionTest {
    @Test
    fun `a compaction runs the CLI compact command and ends with one notice`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.first { it.startsWith("--session-id=") || it.startsWith("--resume=") }.substringAfter('=')
            line(initFrame(id))
            line("""{"type":"system","subtype":"compact_boundary","session_id":"$id"}""")
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        assertTrue(session.features.available(CompactsContext).isInstructionsSupported)

        val compaction = ContextCompaction("keep the\ndecisions")
        val send = async {
            session.features.available(
                SendsPrompts,
            ).send(PromptRequest(RequestId("c"), emptyList(), compaction = compaction))
        }
        runCurrent()
        send.await()

        assertEquals("/compact keep the decisions", fixture.transport.inputs.last())
        val items = session.features.available(SessionHistory).page().items
        assertEquals(NoticeKind.ContextCompacted, items.filterIsInstance<SessionItem.Notice>().single().kind)
        assertTrue(items.filterIsInstance<SessionItem.Message>().none { it.role == MessageRole.User })
        assertTrue(items.filterIsInstance<SessionItem.Message>().none { it.role == MessageRole.Assistant })
        runtime.close()
    }

    @Test
    fun `automatic compaction inside a prompt turn adds a notice`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.first { it.startsWith("--session-id=") || it.startsWith("--resume=") }.substringAfter('=')
            line(initFrame(id))
            line("""{"type":"system","subtype":"compact_boundary","session_id":"$id"}""")
            line(assistantFrame(id))
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))

        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()

        val items = session.features.available(SessionHistory).page().items
        assertEquals(1, items.count { it is SessionItem.Notice })
        runtime.close()
    }
}
