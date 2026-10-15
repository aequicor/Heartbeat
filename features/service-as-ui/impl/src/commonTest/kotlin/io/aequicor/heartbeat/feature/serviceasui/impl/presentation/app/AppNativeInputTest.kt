package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiPress
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceHost
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.inputsFor
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.subscribe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppNativeInputTest {
    @Test
    fun `a native press queued before rebind cannot execute in the new chat`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val surface = fixture.surface
        surface.store.intent(savePress())
        // Rebind before FlowMVI drains its intent channel, as first-chat acceptance may do.
        surface.rebind(AppChatContext.call, NativeInputHost, UiFacts(hasSession = true))
        runCurrent()
        assertFalse(fixture.calls.made.any { it.first == "save" })

        surface.store.intent(savePress())
        runCurrent()
        assertEquals(listOf("save" to AppChatContext.call), fixture.calls.made.filter { it.first == "save" })
    }

    @Test
    fun `subscription input also retains the authority from before enqueue`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val surface = fixture.surface
        val screen = subscribe(surface.store)
        screen.intent(savePress())
        surface.rebind(AppChatContext.call, NativeInputHost, UiFacts(hasSession = true))
        runCurrent()
        assertFalse(fixture.calls.made.any { it.first == "save" })
        screen.emit(savePress())
        runCurrent()
        assertEquals(listOf("save" to AppChatContext.call), fixture.calls.made.filter { it.first == "save" })
    }

    @Test
    fun `a callback from the old rendered state cannot acquire new chat authority`() = runTest {
        val fixture = AppFixture(this)
        runCurrent()
        val surface = fixture.surface
        val screen = subscribe(surface.store)
        val oldInputs = surface.store.inputsFor(screen.states.value)
        surface.rebind(AppChatContext.call, NativeInputHost, UiFacts(hasSession = true))
        runCurrent()

        oldInputs(savePress())
        oldInputs(SurfaceIntent.Input("draft", JsonPrimitive("obsolete")))
        runCurrent()
        assertFalse(fixture.calls.made.any { it.first == "save" })
        assertEquals(JsonPrimitive(""), screen.states.value.values.state["draft"])

        val currentInputs = surface.store.inputsFor(screen.states.value)
        currentInputs(SurfaceIntent.Input("draft", JsonPrimitive("current")))
        currentInputs(savePress())
        runCurrent()
        assertEquals(JsonPrimitive("current"), screen.states.value.values.state["draft"])
        assertEquals(listOf("save" to AppChatContext.call), fixture.calls.made.filter { it.first == "save" })
    }
}

private fun savePress() = SurfaceIntent.Press(UiPress("save", UiAction.Call("save"), UiScope()))

private object NativeInputHost : SurfaceHost {
    override suspend fun prompt(text: String, isSubmitted: Boolean): Boolean = false
    override suspend fun openPanel(name: String, args: JsonObject): Boolean = false
    override suspend fun openSurface(name: String, args: JsonObject): Boolean = false
}
