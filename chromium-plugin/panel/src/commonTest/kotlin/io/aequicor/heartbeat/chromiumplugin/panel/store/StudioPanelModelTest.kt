package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.FakePanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewWriter
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pro.respawn.flowmvi.test.subscribeAndTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioPanelModelTest {
    private val host = FakePanelHost()
    private val writer = StudioViewWriter(generation = "first", maxBytes = 10_000)

    private fun TestScope.model(): StudioPanelModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = HeartbeatStoreFactory(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        host.deliver("""{"type":"status","status":"ready","features":{"studio":true}}""")
        return StudioPanelModel(factory, PanelLink(host), host)
    }

    private fun posted(type: String) = host.posted.filter { it["type"]?.jsonPrimitive?.content == type }

    private fun show(screen: AiStudioScreenState, frames: StudioViewWriter = writer) {
        val view = posted("watch").last().getValue("params").jsonObject.getValue("view")
        frames.write(screen, screen.remoteSeq).forEach { frame ->
            val params = JsonObject(mapOf("view" to view, "watch" to JsonPrimitive("w"), "frame" to frame))
            host.deliver("""{"type":"event","key":"studio","event":"studio.changed","params":$params}""")
        }
    }

    @Test
    fun `capture follows the original pane and a disconnected frame refuses screen commands`() = runTest {
        host.capture = { PAGE }
        model().store.subscribeAndTest {
            runCurrent()
            show(ready())
            runCurrent()
            assertTrue(states.value.isInteractive)
            StudioPanelIntent.CapturePage(0) resultsIn { runCurrent() }
            assertFalse(states.value.isCapturing)
            assertNull(states.value.capturedPage)
            val call = posted("call").single()
            assertEquals("studio.attachPage", call["method"]?.jsonPrimitive?.content)
            assertEquals(JsonPrimitive("first"), call.getValue("params").jsonObject["generation"])
            host.deliver("""{"type":"status","status":"disconnected"}""")
            runCurrent()
            assertFalse(states.value.isInteractive)
            StudioPanelIntent.Screen(AiStudioScreenIntent.Submit(0)) resultsIn { runCurrent() }
            assertEquals(1, posted("call").size)
        }
    }

    @Test
    fun `a delayed capture never attaches to another chat and can be attached explicitly`() = runTest {
        val page = CompletableDeferred<String?>()
        host.capture = { page.await() }
        model().store.subscribeAndTest {
            runCurrent()
            show(ready())
            runCurrent()
            StudioPanelIntent.CapturePage(0) resultsIn { runCurrent() }
            assertTrue(states.value.isCapturing)
            show(ready().copy(panes = persistentListOf(PaneUi(0, sessionId = "another"))))
            runCurrent()
            page.complete(PAGE)
            runCurrent()
            assertEquals(CaptureProblem.DestinationChanged, states.value.captureProblem)
            assertNotNull(states.value.capturedPage)
            assertTrue(posted("call").isEmpty())
            StudioPanelIntent.AttachCaptured resultsIn { runCurrent() }
            assertEquals("studio.attachPage", posted("call").single()["method"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `context selection waits for a supported live draft and remains after refusal`() = runTest {
        host.selection = PAGE
        model().store.subscribeAndTest {
            runCurrent()
            assertNotNull(states.value.capturedPage)
            assertNull(host.selection)
            show(ready().copy(isAttachmentsEnabled = false))
            runCurrent()
            StudioPanelIntent.AttachCaptured resultsIn { runCurrent() }
            assertEquals(CaptureProblem.Unsupported, states.value.captureProblem)
            assertNotNull(states.value.capturedPage)
            assertTrue(posted("call").isEmpty())
            show(ready())
            runCurrent()
            StudioPanelIntent.AttachCaptured resultsIn { runCurrent() }
            assertEquals(1, posted("call").size)
            assertNull(states.value.capturedPage)
        }
    }

    @Test
    fun `an oversized view can be replaced without replay and its draft is restored only explicitly`() = runTest {
        model().store.subscribeAndTest {
            runCurrent()
            show(ready().withDraft(0, "Previous draft"))
            runCurrent()
            val previous = posted("watch").single().getValue("params").jsonObject.getValue("view")
            StudioPanelIntent.Screen(AiStudioScreenIntent.Submit(0)) resultsIn { runCurrent() }
            show(ready().withDraft(0, "x".repeat(20_000)))
            runCurrent()
            assertEquals(StudioViewStatus.TooLarge, states.value.remote.status)
            StudioPanelIntent.NewView resultsIn { runCurrent() }
            assertEquals(2, posted("watch").size)
            assertEquals(1, posted("unwatch").size)
            assertNotEquals(previous, posted("watch").last().getValue("params").jsonObject.getValue("view"))
            show(ready().withDraft(0, "New draft"), StudioViewWriter(generation = "second"))
            runCurrent()
            assertEquals(1, posted("call").size, "No old submit is replayed")
            assertEquals("Previous draft", states.value.recoveryDraft)
            StudioPanelIntent.RecoverDraft resultsIn { runCurrent() }
            assertNull(states.value.recoveryDraft)
            val input = posted("call").last().getValue("params").jsonObject.getValue("intents")
                .jsonArray.single().jsonObject.getValue("intent").jsonObject
            assertEquals(JsonPrimitive("New draft\n\nPrevious draft"), input["text"])
        }
    }

    @Test
    fun `reconnected transport waits for a fresh snapshot before accepting input`() = runTest {
        var captures = 0
        host.capture = {
            captures++
            PAGE
        }
        model().store.subscribeAndTest {
            runCurrent()
            show(ready())
            runCurrent()
            host.deliver("""{"type":"status","status":"disconnected"}""")
            host.deliver("""{"type":"status","status":"ready"}""")
            // No drain: the reducer must reject input before queued connection notifications are collected.
            StudioPanelIntent.Screen(AiStudioScreenIntent.Submit(0)) resultsIn { runCurrent() }
            assertFalse(states.value.isInteractive)
            StudioPanelIntent.CapturePage(0) resultsIn { runCurrent() }
            assertEquals(0, captures)
            assertTrue(posted("call").isEmpty())
            show(ready(), StudioViewWriter(generation = "first"))
            runCurrent()
            assertTrue(states.value.isInteractive)
            assertTrue(posted("call").isEmpty(), "Rejected offline input must not replay after the snapshot")
            StudioPanelIntent.CapturePage(0) resultsIn { runCurrent() }
            assertEquals(1, captures)
            assertEquals("studio.attachPage", posted("call").single()["method"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `pending navigation blocks automatic capture attachment and further capture until its frame`() = runTest {
        val page = CompletableDeferred<String?>()
        var captures = 0
        host.capture = {
            captures++
            page.await()
        }
        model().store.subscribeAndTest {
            runCurrent()
            show(ready())
            runCurrent()
            StudioPanelIntent.CapturePage(0) resultsIn { runCurrent() }
            StudioPanelIntent.Screen(AiStudioScreenIntent.OpenSession("another")) resultsIn { runCurrent() }
            page.complete(PAGE)
            runCurrent()
            assertEquals(CaptureProblem.DestinationChanged, states.value.captureProblem)
            assertNotNull(states.value.capturedPage)
            StudioPanelIntent.CapturePage(0) resultsIn { runCurrent() }
            StudioPanelIntent.AttachCaptured resultsIn { runCurrent() }
            assertEquals(1, captures)
            assertEquals(listOf("studio.intent"), posted("call").map { it["method"]?.jsonPrimitive?.content })
            val call = posted("call").single()
            host.deliver("""{"type":"reply","id":${call.getValue("id")},"result":{"ack":1}}""")
            runCurrent()
            StudioPanelIntent.AttachCaptured resultsIn { runCurrent() }
            assertEquals(1, posted("call").size, "A reply alone does not establish the visible destination")
            show(ready().copy(remoteSeq = 1, panes = persistentListOf(PaneUi(0, sessionId = "another"))))
            runCurrent()
            StudioPanelIntent.AttachCaptured resultsIn { runCurrent() }
            assertNull(states.value.capturedPage)
            assertEquals("studio.attachPage", posted("call").last()["method"]?.jsonPrimitive?.content)
        }
    }

    private fun ready(): AiStudioScreenState {
        val state = AiStudioScreenState()
        val model = ModelUi(
            state.settings.modelId,
            "Model",
            inputSupport = InputSupportUi(mediaTypes = persistentListOf("text/markdown")),
        )
        return state.copy(
            panes = persistentListOf(PaneUi(0)),
            isAttachmentsEnabled = true,
            models = persistentListOf(model),
        )
    }

    private companion object {
        const val PAGE = """{"url":"https://example.com","title":"Page","text":"Body","selection":"Selected"}"""
    }
}
