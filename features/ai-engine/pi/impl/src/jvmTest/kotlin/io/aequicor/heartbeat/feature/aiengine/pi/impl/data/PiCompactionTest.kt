package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiCompactionTest {
    @Test
    fun `a compaction turn sends compact with instructions and settles on compaction_end`() = runTest {
        val fixture = fixture(this)

        fixture.session.send(compaction("c1", "keep decisions"))
        runCurrent()

        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        val index = fixture.connection.commands.indexOf("compact")
        assertEquals("keep decisions", fixture.connection.fields[index].string("customInstructions"))
        fixture.connection.event(record("""{"type":"compaction_start","reason":"manual"}"""))
        fixture.connection.event(record(COMPACTED))
        runCurrent()

        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertEquals(TurnOutcome.Completed, ready.lastTurn?.outcome)
        val notice = fixture.session.history().items.filterIsInstance<SessionItem.Notice>().single()
        assertEquals(NoticeKind.ContextCompacted, notice.kind)
        fixture.session.shutdown()
    }

    @Test
    fun `an aborted compaction ends the turn as cancelled without a notice`() = runTest {
        val fixture = fixture(this)

        fixture.session.send(compaction("c1"))
        runCurrent()
        fixture.connection.event(record("""{"type":"compaction_end","reason":"manual","aborted":true}"""))
        runCurrent()

        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertEquals(TurnOutcome.Cancelled, ready.lastTurn?.outcome)
        assertTrue(fixture.session.history().items.none { it is SessionItem.Notice })
        fixture.session.shutdown()
    }

    @Test
    fun `a refused compact command fails the turn`() = runTest {
        val fixture = fixture(this)
        val refused = EngineFailure.Request(RequestFailureReason.Invalid)

        fixture.session.send(compaction("c1"))
        fixture.connection.compactAck.completeExceptionally(EngineException(refused))
        runCurrent()

        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertEquals(TurnOutcome.Failed(refused), ready.lastTurn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun `automatic compaction inside a prompt turn leaves the turn running`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()

        fixture.connection.event(record("""{"type":"compaction_start","reason":"threshold"}"""))
        fixture.connection.event(record(COMPACTED))
        runCurrent()

        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        assertEquals(1, fixture.session.history().items.count { it is SessionItem.Notice })
        fixture.session.shutdown()
    }

    private suspend fun PiSession.history() =
        assertIs<FeatureAccess.Available<SessionHistory>>(features.resolve(SessionHistory)).feature
            .page(HistoryPageRequest())

    private fun compaction(id: String, instructions: String? = null) =
        PromptRequest(RequestId(id), emptyList(), compaction = ContextCompaction(instructions))

    private companion object {
        const val COMPACTED =
            """{"type":"compaction_end","reason":"manual","result":{"summary":"s","tokensBefore":10},"aborted":false}"""
    }
}
