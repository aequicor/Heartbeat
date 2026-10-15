package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.subscribe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppRebindTest {
    @Test
    fun `a queued page call never enters the new chat and an uncooperative old callback cannot return success`() =
        runTest {
            val fixture = AppFixture(this)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            fixture.calls.reply = { method, context ->
                if (method == "first") withContext(NonCancellable) { release.await() }
                UiMethodResult.Success(JsonPrimitive(context.session?.nativeId ?: "old"))
            }
            val document = fixture.web.opened.single()
            val bridge = document.document.bridge!!
            val first = backgroundScope.async { bridge.call("first", JsonNull) }
            val queued = backgroundScope.async { bridge.call("queued", JsonNull) }
            runCurrent()
            assertTrue(fixture.calls.made.any { it.first == "first" })
            assertFalse(fixture.calls.made.any { it.first == "queued" })
            fixture.bind(AppChatContext)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertIs<BrowserBridgeResult.Failure>(first.await())
            assertIs<BrowserBridgeResult.Failure>(queued.await())
            assertFalse(fixture.calls.made.any { it.first == "queued" })
            assertIs<BrowserBridgeResult.Value>(bridge.call("current", JsonNull))
            assertEquals(AppChatContext.call, fixture.calls.made.last().second)
            assertFalse(document.isClosed)
        }

    @Test
    fun `late old automatic source cannot overwrite new chat data or the draft`() = runTest {
        val fixture = AppFixture(this)
        val release = CompletableDeferred<Unit>()
        fixture.calls.reply = { _, context ->
            if (context.session == null) withContext(NonCancellable) { release.await() }
            UiMethodResult.Success(JsonPrimitive(context.session?.nativeId ?: "obsolete"))
        }
        runCurrent()
        val model = fixture.surface
        val screen = subscribe(model.store)
        val document = fixture.web.opened.single()
        screen.intent(SurfaceIntent.Input("draft", JsonPrimitive("unsaved")))
        runCurrent()
        fixture.bind(AppChatContext)
        runCurrent()
        assertSame(model, fixture.surface)
        assertEquals(JsonPrimitive("chat"), screen.states.value.values.data["snapshot"])
        release.complete(Unit)
        runCurrent()
        assertEquals(JsonPrimitive("chat"), screen.states.value.values.data["snapshot"])
        assertEquals(JsonPrimitive("unsaved"), screen.states.value.values.state["draft"])
        assertFalse(document.updates.contains(JsonPrimitive("obsolete")))
        assertEquals(1, fixture.web.opened.size)
    }

    @Test
    fun `pausing an in-flight source keeps its observer alive until delayed first chat admission`() = runTest {
        val fixture = AppFixture(this)
        val release = CompletableDeferred<Unit>()
        val admitted = MutableStateFlow<Set<UiOwner>>(emptySet())
        fixture.calls.owners = { context -> if (context.session == null) fixture.calls.active else admitted }
        fixture.calls.reply = { _, context ->
            if (context.session == null) withContext(NonCancellable) { release.await() }
            UiMethodResult.Success(JsonPrimitive(context.session?.nativeId ?: "obsolete"))
        }
        runCurrent()
        val model = fixture.surface
        val screen = subscribe(model.store)
        fixture.bind(AppChatContext)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        admitted.value = setOf(PrMap)
        runCurrent()
        assertSame(model, fixture.surface)
        assertEquals(JsonPrimitive("chat"), screen.states.value.values.data["snapshot"])
        assertEquals(1, fixture.web.opened.size)
    }

    @Test
    fun `external bridge cancellation still cancels its callback without revoking the app`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val cancelled = CompletableDeferred<Unit>()
        fixture.calls.reply = { method, _ ->
            if (method == "cancel") {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            UiMethodResult.Success(JsonPrimitive("ok"))
        }
        val bridge = fixture.web.opened.single().document.bridge!!
        val call = backgroundScope.async { bridge.call("cancel", JsonNull) }
        runCurrent()
        call.cancel()
        runCurrent()
        assertTrue(call.isCancelled)
        assertTrue(cancelled.isCompleted)
        assertIs<BrowserBridgeResult.Value>(bridge.call("next", JsonNull))
        assertFalse(fixture.web.opened.single().isClosed)
    }

    @Test
    fun `independent callback cancellation still propagates while its app authority is active`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        fixture.calls.reply = { _, _ -> throw CancellationException("Callback cancelled") }
        val bridge = fixture.web.opened.single().document.bridge!!
        assertFailsWith<CancellationException> { bridge.call("cancelled", JsonNull) }
        assertFalse(fixture.web.opened.single().isClosed)
    }
}
