package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.FakePanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewEncoder
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewFrame
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewSchema
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RemoteStudioStoreTest {
    private val host = FakePanelHost()
    private val app = StudioViewEncoder()

    private fun TestScope.store(): Store<RemoteStudioState, AiStudioScreenIntent, RemoteStudioAction> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = HeartbeatStoreFactory(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        return remoteStudioStore(factory, PanelLink(host), VIEW, retryDelay = 5.seconds)
    }

    private fun show(state: AiStudioScreenState, ack: Long = state.remoteSeq) {
        val frame = StudioViewJson.encodeToJsonElement(
            StudioViewFrame.serializer(),
            checkNotNull(app.frame(state, ack)),
        )
        val params = JsonObject(mapOf("watch" to JsonPrimitive("w"), "view" to JsonPrimitive(VIEW), "frame" to frame))
        host.deliver(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("event"),
                    "key" to JsonPrimitive("studio"),
                    "event" to JsonPrimitive("studio.changed"),
                    "params" to params,
                ),
            ).toString(),
        )
    }

    private fun posted(type: String) = host.posted.filter { it.getValue("type").jsonPrimitive.content == type }

    private fun reply(call: JsonObject, result: String) {
        host.deliver("""{"type":"reply","id":${call.getValue("id")},$result}""")
    }

    @Test
    fun `the view is watched with its schema and mirrors the app's frames`() = runTest {
        store().subscribeAndTest {
            runCurrent()
            val watch = posted("watch").single()
            assertEquals("studio.watch", watch.getValue("method").jsonPrimitive.content)
            assertEquals(
                JsonObject(
                    mapOf("view" to JsonPrimitive(VIEW), "schema" to JsonPrimitive(StudioViewSchema.fingerprint)),
                ),
                watch.getValue("params"),
            )
            assertEquals(StudioViewStatus.Connecting, states.value.status)

            show(AiStudioScreenState().withDraft(0, "from the app"))
            runCurrent()

            assertEquals(StudioViewStatus.Live, states.value.status)
            assertEquals("from the app", states.value.screen?.draft(0))
        }
    }

    @Test
    fun `typing shows at once and is sent as numbered intents`() = runTest {
        store().subscribeAndTest {
            runCurrent()
            show(AiStudioScreenState())
            runCurrent()

            AiStudioScreenIntent.Suggestions.DraftChanged(paneId = 0, text = "hi") resultsIn { runCurrent() }
            assertEquals("hi", states.value.screen?.draft(0))
            val call = posted("call").single()
            assertEquals("studio.intent", call.getValue("method").jsonPrimitive.content)
            val sent = call.getValue("params").jsonObject.getValue("intents").jsonArray.single().jsonObject
            assertEquals(1, sent.getValue("seq").jsonPrimitive.long)

            reply(call, """"result":{"ack":1,"refused":[]}""")
            show(AiStudioScreenState().withDraft(0, "hi").copy(remoteSeq = 1))
            runCurrent()

            assertEquals("hi", states.value.screen?.draft(0))
            assertEquals(1, posted("call").size)
        }
    }

    @Test
    fun `a view the app forgot is watched again and the intents follow its snapshot`() = runTest {
        store().subscribeAndTest {
            runCurrent()
            show(AiStudioScreenState())
            runCurrent()

            AiStudioScreenIntent.Suggestions.DraftChanged(paneId = 0, text = "x") resultsIn { runCurrent() }
            reply(posted("call").single(), """"error":{"code":"${BridgeErrorCode.UNKNOWN_VIEW}"}""")
            runCurrent()
            assertEquals(
                listOf("watch", "call", "unwatch", "watch"),
                host.posted.map { it.getValue("type").jsonPrimitive.content },
            )

            app.reset()
            show(AiStudioScreenState())
            runCurrent()

            val resent = posted("call").last().getValue("params").jsonObject.getValue("intents").jsonArray
            assertEquals(listOf(1L), resent.map { it.jsonObject.getValue("seq").jsonPrimitive.long })
            assertEquals("x", states.value.screen?.draft(0))
        }
    }

    @Test
    fun `refusals are shown, retried later, and another schema is final`() = runTest {
        store().subscribeAndTest {
            runCurrent()
            host.deliver("""{"type":"watchFailed","key":"studio","error":{"code":"${BridgeErrorCode.BUSY}"}}""")
            runCurrent()
            assertEquals(StudioViewStatus.Busy, states.value.status)

            advanceTimeBy(6.seconds)
            assertEquals(2, posted("watch").size)
            host.deliver(
                """{"type":"watchFailed","key":"studio","error":{"code":"${BridgeErrorCode.UNSUPPORTED_PROTOCOL}"}}""",
            )
            runCurrent()
            assertEquals(StudioViewStatus.Incompatible, states.value.status)

            advanceTimeBy(60.seconds)
            assertEquals(2, posted("watch").size)
        }
    }

    @Test
    fun `a timed out send retries after resync without new user input`() = runTest {
        store().subscribeAndTest {
            runCurrent()
            show(AiStudioScreenState())
            runCurrent()
            AiStudioScreenIntent.ToggleSearch resultsIn { runCurrent() }
            reply(posted("call").single(), "\"error\":{\"code\":\"timeout\"}")
            advanceTimeBy(6.seconds)
            runCurrent()
            assertEquals(2, posted("watch").size)
            app.reset()
            show(AiStudioScreenState())
            runCurrent()
            assertEquals(2, posted("call").size)
        }
    }

    private companion object {
        const val VIEW = "view-1"
    }
}
