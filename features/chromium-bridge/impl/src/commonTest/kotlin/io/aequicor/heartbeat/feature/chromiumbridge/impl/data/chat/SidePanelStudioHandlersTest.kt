package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteOpen
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemotePage
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteRefusal
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeClient
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeIncomingEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class SidePanelStudioHandlersTest {
    private val studio = FakeStudio()
    private val watches = BridgeWatches()
    private val handlers = SidePanelStudioHandlers(studio, watches)

    private suspend fun call(session: BridgeSession, method: String, params: JsonObject) =
        handlers.handle(BridgeCall(method, params, session))

    private fun open(view: String = "v1", schema: String = "abc") = buildJsonObject {
        put("view", view)
        put("schema", schema)
    }

    @Test
    fun `a watched view relays its frames untouched with the watch and the view`() = runTest {
        val session = FakeSession(this)
        val frame = buildJsonObject { put("@type", "snapshot") }
        studio.frames = flowOf(frame)

        val reply = call(session, "studio.watch", open()) as BridgeReply.Ok
        runCurrent()
        assertEquals(emptyList(), session.emitted)
        reply.afterSend?.invoke(true)
        runCurrent()

        val watch = reply.result.jsonObject.getValue("watch").jsonPrimitive.content
        val (event, params) = session.emitted.single()
        assertEquals("studio.changed", event)
        assertEquals(JsonPrimitive(watch), params["watch"])
        assertEquals(JsonPrimitive("v1"), params["view"])
        assertEquals(frame, params["frame"])
    }

    @Test
    fun `a failed reply or cancellation before activation releases the reserved watch`() = runTest {
        val session = FakeSession(this)
        studio.frames = flowOf(buildJsonObject { put("snapshot", true) })
        val failed = call(session, "studio.watch", open()) as BridgeReply.Ok
        val failedId = failed.result.jsonObject.getValue("watch").jsonPrimitive.content
        failed.afterSend?.invoke(false)
        runCurrent()
        assertEquals(false, watches.cancel(session, failedId))
        assertEquals(emptyList(), session.emitted)

        val cancelled = call(session, "studio.watch", open()) as BridgeReply.Ok
        val cancelledId = cancelled.result.jsonObject.getValue("watch").jsonPrimitive.content
        assertEquals(true, watches.cancel(session, cancelledId))
        cancelled.afterSend?.invoke(true)
        runCurrent()
        assertEquals(emptyList(), session.emitted)
    }

    @Test
    fun `refused views map to bridge error codes`() = runTest {
        val session = FakeSession(this)
        val codes = listOf(
            StudioRemoteRefusal.Unavailable to BridgeErrorCode.UNAVAILABLE,
            StudioRemoteRefusal.Incompatible to BridgeErrorCode.UNSUPPORTED_PROTOCOL,
            StudioRemoteRefusal.Busy to BridgeErrorCode.BUSY,
        )
        for ((refusal, code) in codes) {
            studio.refusal = refusal

            assertEquals(code, (call(session, "studio.watch", open()) as BridgeReply.Refused).code)
        }
    }

    @Test
    fun `intents reach the view in one batch and the acknowledgement comes back`() = runTest {
        val session = FakeSession(this)
        val params = buildJsonObject {
            put("view", "v1")
            put("generation", "g1")
            putJsonArray("intents") {
                addJsonObject {
                    put("seq", 1)
                    put("intent", buildJsonObject { put("@type", "AiStudioScreenIntent.Retry") })
                }
                addJsonObject {
                    put("seq", 2)
                    put("intent", JsonArray(listOf(JsonPrimitive("x"))))
                }
            }
        }

        val reply = call(session, "studio.intent", params) as BridgeReply.Ok
        studio.isKnown = false
        val unknown = call(session, "studio.intent", params) as BridgeReply.Refused

        assertEquals(listOf(1L, 2L), studio.sent.single().map { it.seq })
        val ack = buildJsonObject {
            put("ack", 2)
            putJsonArray("refused") { add(2) }
        }
        assertEquals(ack, reply.result)
        assertEquals(BridgeErrorCode.UNKNOWN_VIEW, unknown.code)
    }

    @Test
    fun `page capture keeps pane sequence generation and validates browser bounds`() = runTest {
        val session = FakeSession(this)
        val params = buildJsonObject {
            put("view", "v1")
            put("generation", "g1")
            put("seq", 4)
            put("paneId", 7)
            put(
                "page",
                buildJsonObject {
                    put("url", "https://example.com")
                    put("title", "Page")
                    put("text", "Body")
                    put("selection", "Selected")
                },
            )
        }
        val reply = call(session, "studio.attachPage", params) as BridgeReply.Ok
        assertEquals(JsonPrimitive(4), reply.result.jsonObject["ack"])
        assertEquals(StudioRemotePage("https://example.com", "Page", "Body", "Selected"), studio.attached)
        studio.isKnown = false
        assertEquals(
            BridgeErrorCode.UNKNOWN_VIEW,
            (call(session, "studio.attachPage", params) as BridgeReply.Refused).code,
        )
        val bad = JsonObject(params + ("page" to buildJsonObject { put("url", "file:///secret") }))
        kotlin.test.assertFailsWith<IllegalArgumentException> { call(session, "studio.attachPage", bad) }
    }

    private class FakeStudio : StudioRemoteScreens {
        var refusal: StudioRemoteRefusal? = null
        var frames: Flow<JsonObject> = flowOf()
        var isKnown = true
        val sent = mutableListOf<List<StudioRemoteIntent>>()

        override val availability: Flow<Boolean> = flowOf(true)

        override suspend fun open(viewId: String, schema: String): StudioRemoteOpen =
            refusal?.let(StudioRemoteOpen::Refused) ?: StudioRemoteOpen.Opened(frames)

        var attached: StudioRemotePage? = null

        override suspend fun attachPage(
            viewId: String,
            generation: String,
            seq: Long,
            paneId: Int,
            page: StudioRemotePage,
        ): StudioRemoteReply {
            assertEquals("v1", viewId)
            assertEquals("g1", generation)
            assertEquals(7, paneId)
            if (!isKnown) return StudioRemoteReply.UnknownView
            attached = page
            return StudioRemoteReply.Applied(seq, emptyList())
        }

        override suspend fun send(
            viewId: String,
            generation: String,
            intents: List<StudioRemoteIntent>,
        ): StudioRemoteReply {
            assertEquals("g1", generation)
            if (!isKnown) return StudioRemoteReply.UnknownView
            sent += intents
            return StudioRemoteReply.Applied(intents.maxOf { it.seq }, listOf(2))
        }
    }

    private class FakeSession(test: TestScope) : BridgeSession {
        val emitted = mutableListOf<Pair<String, JsonObject>>()
        override val id: Long = 1
        override val origin: String = "chrome-extension://test/"
        override val client: StateFlow<BridgeClient?> =
            MutableStateFlow(BridgeClient(origin, "0.1.0", "Chrome", "140", setOf("chat")))
        override val events: SharedFlow<BridgeIncomingEvent> = MutableSharedFlow()
        override val scope: CoroutineScope = test.backgroundScope

        override fun accept(client: BridgeClient) = Unit

        override suspend fun emit(event: String, params: JsonObject) {
            emitted += event to params
        }

        override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement =
            JsonArray(emptyList())
    }
}
