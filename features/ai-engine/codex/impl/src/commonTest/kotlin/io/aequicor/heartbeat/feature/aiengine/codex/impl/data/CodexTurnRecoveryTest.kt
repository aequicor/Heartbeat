package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CodexTurnRecoveryTest {
    @Test
    fun `completion during adoption is discovered by the second native snapshot`() = runTest {
        val before = Fixture(this)
        val original = before.open()
        original.feature(SendsPrompts).send(Prompt)
        val receipt = assertNotNull(original.feature(RestoresSessionTurns).checkpoint(Prompt.id))
        val after = Fixture(this)
        after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "inProgress".json()))
        val resumed = after.runtime.attach(original.ref, ResumeSessionRequest(after.target))
        val handler = after.wire.handler
        after.wire.handler = { message ->
            handler(message)
            if (message.text("method") == "thread/read") {
                after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "completed".json()))
                after.event("turn/completed", "turn" to after.threadTurns.single())
            }
        }
        assertEquals(
            TurnInspection.Observed(Prompt.id, TurnOutcome.Completed),
            resumed.feature(RestoresSessionTurns).inspect(receipt),
        )
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
    }

    @Test
    fun `native receipt survives recreation and recovers completed outcome`() = runTest {
        val before = Fixture(this)
        val original = before.open()
        original.feature(SendsPrompts).send(Prompt)
        val receipt = assertNotNull(original.feature(RestoresSessionTurns).checkpoint(Prompt.id))
        val after = Fixture(this)
        after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "completed".json()))
        val resumed = after.runtime.attach(original.ref, ResumeSessionRequest(after.target))
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
        assertEquals(
            TurnInspection.Observed(Prompt.id, TurnOutcome.Completed),
            resumed.feature(RestoresSessionTurns).inspect(receipt),
        )
    }

    @Test
    fun `only a receipt matched running native turn may be adopted`() = runTest {
        val before = Fixture(this)
        val original = before.open()
        original.feature(SendsPrompts).send(Prompt)
        val receipt = assertNotNull(original.feature(RestoresSessionTurns).checkpoint(Prompt.id))
        val after = Fixture(this)
        after.threadTurns = listOf(json("id" to "native-turn".json(), "status" to "inProgress".json()))
        val resumed = after.runtime.attach(original.ref, ResumeSessionRequest(after.target))
        val recovery = resumed.feature(RestoresSessionTurns)
        assertEquals(TurnInspection.Unknown, recovery.inspect(null))
        assertEquals(TurnInspection.Observed(Prompt.id, null), recovery.inspect(receipt))
        assertEquals(Prompt.id, assertIs<ActiveSessionState.Running>(resumed.state.value).turn.request)
    }
}
