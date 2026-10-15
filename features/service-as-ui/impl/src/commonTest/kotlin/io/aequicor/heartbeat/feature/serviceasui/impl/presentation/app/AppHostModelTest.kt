package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.Other
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.subscribe
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppHostModelTest {
    @Test
    fun `start app owns one document without UI subscriptions and closing the modal retains it`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val surface = fixture.surface
        val document = fixture.web.opened.single()
        assertTrue(fixture.model.state.value.isStart)
        assertEquals(JsonPrimitive("start"), document.updates.last())
        fixture.model.intent(AppHostIntent.Open)
        runCurrent()
        assertTrue(fixture.model.state.value.isExpanded)
        fixture.model.intent(AppHostIntent.Close(fixture.model.state.value.sequence))
        runCurrent()
        assertFalse(fixture.model.state.value.isExpanded)
        assertSame(surface, fixture.surface)
        assertFalse(document.isClosed)
        assertEquals(1, fixture.web.opened.size)
    }

    @Test
    fun `first chat keeps local input and document identity but calls with the newly admitted context`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val model = fixture.surface
        val screen = subscribe(model.store)
        screen.intent(SurfaceIntent.Input("draft", JsonPrimitive("40 x 10")))
        runCurrent()
        val document = fixture.web.opened.single()
        val key = fixture.model.state.value.key
        fixture.bind(AppChatContext)
        runCurrent()
        assertSame(model, fixture.surface)
        assertEquals(key, fixture.model.state.value.key)
        assertEquals(JsonPrimitive("40 x 10"), screen.states.value.values.state["draft"])
        assertEquals(JsonPrimitive("chat"), document.updates.last())
        assertFalse(document.isClosed)
        assertEquals(1, fixture.web.opened.size)
        assertIs<BrowserBridgeResult.Value>(document.document.bridge!!.call("save", JsonNull))
        assertEquals(AppChatContext.call, fixture.calls.made.last().second)
        fixture.cards.live.value = fixture.receipt()
        runCurrent()
        assertSame(model, fixture.surface, "The first receipt only adds durable navigation metadata")
        assertEquals("receipt", fixture.machine.ready.apps.getValue(AppHost).cardId)
    }

    @Test
    fun `switching actual chats destroys the old app and cannot reuse its pending bridge`() = runTest {
        val fixture = AppFixture(this, AppChatContext)
        runCurrent()
        val old = fixture.surface
        val document = fixture.web.opened.single()
        fixture.bind(AppChatContext.copy(call = AppChatContext.call.copy(session = AppChat.copy(nativeId = "other"))))
        assertIs<BrowserBridgeResult.Failure>(document.document.bridge!!.call("stale", JsonNull))
        runCurrent()
        assertNotSame(old, fixture.surface)
        assertTrue(document.isClosed)
        assertTrue(fixture.calls.made.none { it.first == "stale" })
    }

    @Test
    fun `disabled feature detached owner changed spec and changed arguments release retained documents`() = runTest {
        val fixture = AppFixture(this, AppChatContext)
        runCurrent()
        val first = fixture.web.opened.single()
        fixture.calls.active.value = emptySet()
        runCurrent()
        assertTrue(first.isClosed)
        assertTrue(fixture.model.surfaces.value.isEmpty())
        fixture.calls.active.value = setOf(PrMap)
        runCurrent()
        val second = fixture.web.opened.last()
        fixture.cards.live.value = fixture.receipt(buildJsonObject { put("workout", 2) })
        runCurrent()
        assertTrue(second.isClosed)
        val third = fixture.web.opened.last()
        val changed = AppSurface.copy(
            content = AppSurface.content.copy(spec = AppSurface.content.spec.replace("draft'>", "new'>")),
        )
        fixture.machine.state.value = fixture.machine.ready.copy(library = UiLibrary(listOf(changed), revision = 1))
        runCurrent()
        assertTrue(third.isClosed)
        val fourth = fixture.web.opened.last()
        fixture.machine.state.value = ServiceUiState.Disabled()
        runCurrent()
        assertTrue(fourth.isClosed)
        assertTrue(fixture.model.surfaces.value.isEmpty())
    }

    @Test
    fun `returning from pending first chat to start creates a usable fresh authority`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val previous = fixture.surface
        val document = fixture.web.opened.single()
        fixture.calls.owners = { context ->
            if (context.session == AppChat) emptyFlow() else fixture.calls.active
        }
        fixture.bind(AppChatContext)
        runCurrent()
        fixture.bind(AppStart)
        runCurrent()
        assertTrue(document.isClosed)
        assertNotSame(previous, fixture.surface)
        val bridge = fixture.web.opened.last().document.bridge!!
        assertIs<BrowserBridgeResult.Value>(bridge.call("save", JsonNull))
        assertEquals(AppStart.call, fixture.calls.made.last().second)
    }

    @Test
    fun `a paused first chat cannot pass its start document on to a different chat`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val first = fixture.surface
        val document = fixture.web.opened.single()
        fixture.calls.owners = { context ->
            if (context.session == AppChat) emptyFlow() else fixture.calls.active
        }
        fixture.bind(AppChatContext)
        runCurrent()
        assertFalse(document.isClosed)
        val other = AppChatContext.copy(call = AppChatContext.call.copy(session = AppChat.copy(nativeId = "other")))
        fixture.bind(other)
        runCurrent()
        assertTrue(document.isClosed)
        assertNotSame(first, fixture.surface)
        assertEquals(other.call, fixture.calls.made.last().second)
    }

    @Test
    fun `unrelated owners and library writes keep the model while slow rebind admission revokes its bridge`() =
        runTest {
            val fixture = AppFixture(this)
            runCurrent()
            val model = fixture.surface
            val document = fixture.web.opened.single()
            fixture.calls.active.value = setOf(PrMap, Other)
            fixture.machine.state.value = fixture.machine.ready.copy(
                library = fixture.machine.ready.library.copy(revision = 1),
            )
            runCurrent()
            assertSame(model, fixture.surface)
            fixture.calls.owners = { emptyFlow() }
            fixture.bind(AppChatContext)
            assertIs<BrowserBridgeResult.Failure>(document.document.bridge!!.call("stale", JsonNull))
            runCurrent()
            assertFalse(document.isClosed, "First chat admission may retain the paused DOM")
            assertTrue(fixture.machine.ready.apps.isEmpty())
            fixture.machine.send(ServiceUiIntent.Public.UnbindHost(AppHost))
            runCurrent()
            assertTrue(document.isClosed)
        }
}
