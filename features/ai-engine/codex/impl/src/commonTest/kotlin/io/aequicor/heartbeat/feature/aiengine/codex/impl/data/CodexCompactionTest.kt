package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexCompactionTest {
    @Test
    fun `a compaction starts a native compaction turn without turn input`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        assertFalse(session.feature(CompactsContext).isInstructionsSupported)

        val send = async { session.feature(SendsPrompts).send(Compaction) }
        runCurrent()
        val request = fixture.wire.written.single { it.text("method") == CODEX_COMPACT_METHOD }
        assertEquals("thread", request.obj("params").text("threadId"))
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        fixture.wire.reply(request, json())
        fixture.event("turn/started", "turn" to json("id" to "compact-turn".json()))
        val turn = send.await()
        runCurrent()

        val item = json("id" to "c1".json(), "type" to "contextCompaction".json())
        fixture.event("item/completed", "turnId" to "compact-turn".json(), "item" to item)
        fixture.event("turn/completed", "turn" to json("id" to "compact-turn".json(), "status" to "completed".json()))
        runCurrent()

        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(turn, ready.lastTurn?.id)
        assertEquals(TurnOutcome.Completed, ready.lastTurn?.outcome)
        val notice = session.feature(SessionHistory).page().items.filterIsInstance<SessionItem.Notice>().single()
        assertEquals(NoticeKind.ContextCompacted, notice.kind)
        fixture.runtime.close()
    }

    private companion object {
        val Compaction = PromptRequest(RequestId("compact"), emptyList(), compaction = ContextCompaction())
    }
}
