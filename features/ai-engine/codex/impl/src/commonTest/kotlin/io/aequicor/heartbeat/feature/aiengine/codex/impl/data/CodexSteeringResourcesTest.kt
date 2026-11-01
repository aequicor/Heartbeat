package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteeringRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteersTurns
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CodexSteeringResourcesTest {
    @Test
    fun `first image correction discovers support after a text only initial turn`() = runTest {
        val fixture = Fixture(this).withImageModel()
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
        assertTrue(fixture.wire.written.none { it.text("method") == "model/list" })
        session.feature(SteersTurns).steer(turn, SteeringRequest(RequestId("image"), imageParts))
        val input = fixture.wire.written.single { it.text("method") == "turn/steer" }.obj("params")["input"]
        val native = assertIs<JsonObject>(assertIs<JsonArray>(input).single())
        assertEquals("localImage", native.text("type"))
        assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/start" })
        assertEquals(turn, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
    }

    @Test
    fun `item reference persistence failure reports history unavailable and closes execution connection`() = runTest {
        val writes = mutableListOf<String>()
        val resources = object : PromptResourceHistory {
            override suspend fun remember(session: SessionRef, nativeId: String, parts: List<ContentPart>) {
                writes += nativeId
                check(!nativeId.startsWith("item:")) { "History storage unavailable" }
            }
            override suspend fun parts(session: SessionRef, nativeId: String): List<ContentPart>? = null
        }
        val fixture = Fixture(this, resourceHistory = resources).withImageModel()
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt.copy(parts = imageParts))
        fixture.event(
            "item/started",
            "turnId" to "native-turn".json(),
            "item" to json(
                "id" to "original".json(),
                "type" to "userMessage".json(),
                "content" to JsonArray(
                    listOf(json("type" to "localImage".json(), "path" to "/private/image.png".json())),
                ),
            ),
        )
        runCurrent()
        assertTrue(writes.contains("item:original"))
        val unavailable = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(EngineFailure.History(HistoryFailureReason.Unavailable), unavailable.failure)
        assertEquals(turn, unavailable.activeTurn?.id)
        assertTrue(fixture.wire.peers.last().isClosed)
        assertTrue(!fixture.runtime.isClosed)
    }

    private fun Fixture.withImageModel(): Fixture = apply {
        modelList = listOf(
            json("model" to "model".json(), "inputModalities" to JsonArray(listOf("image".json()))),
        )
        resources = ResourceResolver {
            ResolvedResource("image.png", "image/png", byteArrayOf(1), "/private/image.png")
        }
    }

    private val imageParts = listOf(ContentPart.Image(ResourceRef("attachment:image", "image/png")))
}
