package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteeringRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteersTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CodexSteeringTest {
    @Test
    fun `steering requires the expected native turn without starting another one`() = runTest {
        val fixture = Fixture(this)
        val previous = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "turn/steer") {
                fixture.wire.reply(message, json("turnId" to "native-turn".json()))
            } else {
                previous(message)
            }
        }
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val correction = SteeringRequest(RequestId("correction"), listOf(ContentPart.Text("Use compact rows")))
        session.feature(SteersTurns).steer(turn, correction)
        val params = fixture.wire.written.single { it.text("method") == "turn/steer" }.obj("params")
        assertEquals("native-turn", params.text("expectedTurnId"))
        assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/start" })
        assertEquals(turn, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        assertFailsWith<EngineException> { session.feature(SteersTurns).steer(TurnId("stale"), correction) }
        assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/steer" })
    }

    @Test
    fun `steered message does not inherit initial attachments and repeated events keep its own input`() = runTest {
        val history = CodexHistory()
        val turn = TurnId("turn")
        val image = listOf(ContentPart.Image(ResourceRef("attachment:initial", "image/png")))
        history.rememberOriginals(turn, image)
        history.nativeItem(userMessage("first", "original"), turn, isStarted = true)
        val correction = SteeringRequest(RequestId("correction"), listOf(ContentPart.Text("Correct this")))
        history.inputs.rememberSteering(turn, correction)
        history.nativeItem(userMessage("second", "Correct this"), turn, isStarted = true)
        history.nativeItem(userMessage("first", "original"), turn)
        history.nativeItem(userMessage("second", "Correct this"), turn)
        val messages = history.page().items.filterIsInstance<SessionItem.Message>()
        assertEquals(image, messages[0].parts)
        assertEquals(correction.parts, messages[1].parts)
    }

    private fun userMessage(id: String, text: String) = json(
        "id" to id.json(),
        "type" to "userMessage".json(),
        "content" to JsonArray(listOf(json("type" to "text".json(), "text" to text.json()))),
    )
}
