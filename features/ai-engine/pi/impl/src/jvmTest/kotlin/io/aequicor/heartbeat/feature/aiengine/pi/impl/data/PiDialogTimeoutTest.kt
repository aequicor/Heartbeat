package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiDialogTimeoutTest {
    @Test
    fun `expired dialog disappears while another question and the turn remain active`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()
        fixture.connection.event(
            record(
                """{"type":"extension_ui_request","id":"expires","method":"confirm",
                    "title":"Continue?","timeout":1000}""",
            ),
        )
        fixture.connection.event(
            record(
                """{"type":"extension_ui_request","id":"keep","method":"input","title":"Name?"}""",
            ),
        )
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        val waiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals(listOf("keep"), waiting.requests.map { it.id.value })
        assertTrue(fixture.connection.sent.isEmpty())
        fixture.session.shutdown()
    }

    @Test
    fun `expired native approval withdraws its buttons without approving the call`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()
        fixture.connection.event(JsonObject(approval("expires") + ("timeout" to JsonPrimitive(1000))))
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        advanceTimeBy(1000)
        runCurrent()
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        assertTrue(fixture.connection.sent.isEmpty())
        fixture.session.shutdown()
    }
}
