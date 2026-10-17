package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ButtonStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.QuickActionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.inertQuickAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.Other
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppQuickActionsTest {
    private val host = UiHostId("pane-1")

    @Test
    fun `a host reads only the source attached for it until the handle is disposed`() = runTest {
        val board = ProfileAppQuickActionBoard()
        val seen = mutableListOf<AppQuickActionsUi>()
        backgroundScope.launch { board.observe(host).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(AppQuickActionsUi()), seen)

        val source = Source(AppQuickActionsUi(persistentListOf(inertQuickAction("a", "a")), isAvailable = true))
        val handle = board.attach(host, source)
        board.attach(UiHostId("other"), Source(AppQuickActionsUi(persistentListOf(inertQuickAction("b", "b")))))
        runCurrent()
        assertEquals(listOf("a"), seen.last().actions.map { it.id })

        source.state.value = AppQuickActionsUi(persistentListOf(inertQuickAction("c", "c")), isAvailable = true)
        runCurrent()
        assertEquals(listOf("c"), seen.last().actions.map { it.id })

        handle.dispose()
        runCurrent()
        assertTrue(seen.last().isEmpty)
    }

    @Test
    fun `the newest source of a host wins and a stale handle does not detach it`() = runTest {
        val board = ProfileAppQuickActionBoard()
        val old = Source(AppQuickActionsUi(persistentListOf(inertQuickAction("old", "old"))))
        val new = Source(AppQuickActionsUi(persistentListOf(inertQuickAction("new", "new"))))
        val oldHandle = board.attach(host, old)
        val newHandle = board.attach(host, new)
        val seen = mutableListOf<AppQuickActionsUi>()
        backgroundScope.launch { board.observe(host).collect { seen += it } }
        runCurrent()
        assertEquals(listOf("new"), seen.last().actions.map { it.id })

        oldHandle.dispose()
        board.send(host, SurfaceIntent.DismissNotice)
        runCurrent()
        assertEquals(listOf("new"), seen.last().actions.map { it.id })
        assertEquals(listOf<SurfaceIntent>(SurfaceIntent.DismissNotice), new.sent)
        assertEquals(emptyList(), old.sent)

        newHandle.dispose()
        board.send(host, SurfaceIntent.DismissNotice)
        runCurrent()
        assertTrue(seen.last().isEmpty)
        assertEquals(1, new.sent.size)
    }

    @Test
    fun `an app publishes its visible quick actions and withdraws them with its selection`() = runTest {
        val fixture = AppFixture(this, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()

        val published = shown.value
        assertEquals(listOf("next"), published.actions.map { it.id })
        assertEquals("Next plan", published.actions.single().label)
        assertEquals(ButtonStyleUi.Primary, published.actions.single().style)
        assertTrue(published.isAvailable)

        fixture.calls.active.value = setOf(Other)
        runCurrent()
        assertTrue(shown.value.isEmpty)

        fixture.calls.active.value = setOf(PrMap)
        runCurrent()
        assertEquals(listOf("next"), shown.value.actions.map { it.id })
    }

    @Test
    fun `a press from the composer runs the owner method of the app and updates its actions`() = runTest {
        val fixture = AppFixture(this, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()
        val before = shown.value

        fixture.board.send(host, press(before, before.actions.single()))
        runCurrent()

        assertEquals(listOf(AppStart.call), fixture.advances())
        assertEquals("Next start", shown.value.actions.single().label)
        assertFalse(shown.value.actions.single().isBusy)
    }

    @Test
    fun `a press drawn before the app changed context is dropped and a fresh one runs there`() = runTest {
        val fixture = AppFixture(this, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()
        val stale = shown.value
        val model = fixture.surface

        fixture.bind(AppChatContext)
        runCurrent()
        assertSame(model, fixture.surface, "The first chat keeps the app")
        val fresh = shown.value
        assertNotSame(stale.authority, fresh.authority)

        fixture.board.send(host, press(stale, stale.actions.single()))
        runCurrent()
        assertEquals(emptyList(), fixture.advances())

        fixture.board.send(host, press(fresh, fresh.actions.single()))
        runCurrent()
        assertEquals(listOf(AppChatContext.call), fixture.advances())
    }

    @Test
    fun `the same action sent twice from the composer runs once until the first call ends`() = runTest {
        val fixture = AppFixture(this, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()
        val gate = CompletableDeferred<UiMethodResult>()
        fixture.holdAdvance(gate)
        val drawn = shown.value

        fixture.board.send(host, press(drawn, drawn.actions.single()))
        fixture.board.send(host, press(drawn, drawn.actions.single()))
        runCurrent()
        assertEquals(1, fixture.advances().size)
        assertTrue(shown.value.actions.single().isBusy)

        gate.complete(UiMethodResult.Success(JsonPrimitive("review")))
        runCurrent()
        assertFalse(shown.value.actions.single().isBusy)
        fixture.board.send(host, press(shown.value, shown.value.actions.single()))
        runCurrent()
        assertEquals(2, fixture.advances().size)
    }

    @Test
    fun `a running action is revoked when the app moves to another context and nothing stays busy`() = runTest {
        val fixture = AppFixture(this, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()
        val gate = CompletableDeferred<UiMethodResult>()
        fixture.holdAdvance(gate)
        val drawn = shown.value
        fixture.board.send(host, press(drawn, drawn.actions.single()))
        runCurrent()
        assertTrue(shown.value.actions.single().isBusy)

        fixture.bind(AppChatContext)
        runCurrent()
        gate.complete(UiMethodResult.Success(JsonPrimitive("late")))
        runCurrent()

        assertFalse(shown.value.actions.single().isBusy)
        assertEquals("Next plan", shown.value.actions.single().label, "The late result is discarded")
        fixture.calls.reply = { _, _ -> UiMethodResult.Success(JsonPrimitive("fresh")) }
        fixture.board.send(host, press(shown.value, shown.value.actions.single()))
        runCurrent()
        assertEquals(listOf(AppStart.call, AppChatContext.call), fixture.advances())
    }

    @Test
    fun `another project replaces the app and a press drawn for the old one is dropped`() = runTest {
        val project = AppChatContext.copy(call = AppChatContext.call.copy(workspace = WorkspaceRef("one")))
        val fixture = AppFixture(this, project, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()
        val stale = shown.value
        assertEquals(listOf("next"), stale.actions.map { it.id })

        val other = project.copy(call = project.call.copy(workspace = WorkspaceRef("two")))
        fixture.bind(other)
        runCurrent()
        fixture.board.send(host, press(stale, stale.actions.single()))
        runCurrent()
        assertEquals(emptyList(), fixture.advances())

        val fresh = shown.value
        fixture.board.send(host, press(fresh, fresh.actions.single()))
        runCurrent()
        assertEquals(listOf(other.call), fixture.advances())
    }

    @Test
    fun `a failed quick action leaves its notice for the composer until it is dismissed`() = runTest {
        val fixture = AppFixture(this, app = QuickAppSurface)
        val shown = fixture.observeQuickActions(this)
        runCurrent()
        fixture.calls.reply = { _, _ -> UiMethodResult.UserError("Sign in to gh") }
        val before = shown.value

        fixture.board.send(host, press(before, before.actions.single()))
        runCurrent()
        val failed = shown.value
        assertEquals(NoticeUi.Message("Sign in to gh"), failed.notice)

        fixture.board.send(host, SurfaceIntent.Bound(failed.authority!!, SurfaceIntent.DismissNotice))
        runCurrent()
        assertNull(shown.value.notice)
    }

    private fun press(drawn: AppQuickActionsUi, action: QuickActionUi): SurfaceIntent =
        SurfaceIntent.Bound(checkNotNull(drawn.authority), SurfaceIntent.RunQuickAction(action.id))

    /** The contexts the owner's `advance` method was called in. */
    private fun AppFixture.advances() = calls.made.filter { it.first == "advance" }.map { it.second }

    /** Makes `advance` wait for [gate] while every other method answers at once. */
    private fun AppFixture.holdAdvance(gate: CompletableDeferred<UiMethodResult>) {
        calls.reply = { method, _ -> if (method == "advance") gate.await() else UiMethodResult.Success(JsonNull) }
    }

    /** Observes what the app of [AppHost] publishes, as the composer slot does. */
    private fun AppFixture.observeQuickActions(scope: TestScope): MutableStateFlow<AppQuickActionsUi> {
        val latest = MutableStateFlow(AppQuickActionsUi())
        scope.backgroundScope.launch { board.observe(AppHost).collect { latest.value = it } }
        return latest
    }

    private class Source(initial: AppQuickActionsUi) : AppQuickActionsSource {
        override val state = MutableStateFlow(initial)
        val sent = mutableListOf<SurfaceIntent>()

        override fun send(intent: SurfaceIntent) {
            sent += intent
        }
    }
}
