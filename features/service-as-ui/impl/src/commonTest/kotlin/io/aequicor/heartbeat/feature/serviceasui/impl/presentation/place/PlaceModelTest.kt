package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiPanelRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSlotRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPanel
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.impl.data.UnsupportedUiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppQuickActionsSource
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppQuickActionsUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.ProfileAppQuickActionBoard
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ButtonStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.QuickActionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiPress
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceInputBinding
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceSession
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceModelTest {
    private val host = UiHostId("pane-1")
    private val dock = UiDockId("side")
    private val docked = UiHostContext(UiCallContext(), dock)
    private val start = surface("start", UiPlacement.Start)
    private val map = surface("map", UiPlacement.Panel, launchers = setOf(UiSlot.Start), at = 1)
    private val details = surface("details", UiPlacement.Modal, launchers = setOf(UiSlot.Start), at = 2)
    private val library = UiLibrary(listOf(start, map, details))
    private val slot = ServiceUiSlotRoute(host.value, UiSlot.Start)

    @Test
    fun `a slot draws its surfaces and launchers and restarts a surface when it changes`() = runTest {
        val fixture = Fixture(this, ServiceUiPlace.of(slot), ready(docked))
        val place = fixture.subscribe()
        assertEquals(PlaceState(), place.states.value)

        fixture.calls.report(docked.call, setOf(PrMap))
        runCurrent()
        val state = place.states.value
        assertEquals(listOf("START"), state.surfaces.map { it.title })
        assertEquals(listOf(map.id.value, details.id.value), state.launchers.map { it.surface })
        val drawing = state.surfaces.single().key
        val first = fixture.model.surfaces.value.getValue(drawing)

        fixture.machine.state.value = ready(docked, UiLibrary(listOf(start.copy(revision = 2), map, details)))
        runCurrent()
        val again = place.states.value.surfaces.single().key
        assertNotEquals(drawing, again)
        assertTrue(fixture.model.surfaces.value.getValue(again) !== first)
        assertEquals(setOf(again), fixture.model.surfaces.value.keys)

        fixture.calls.report(docked.call, emptySet())
        runCurrent()
        assertEquals(PlaceState(), place.states.value)
        assertEquals(emptySet(), fixture.model.surfaces.value.keys)
    }

    @Test
    fun `context presence resolves without mounting the UI or loading surface models`() = runTest {
        val summary = surface("summary", UiPlacement.Context)
        val library = UiLibrary(listOf(summary), revision = 3)
        val fixture = Fixture(
            this,
            ServiceUiPlace.of(ServiceUiSlotRoute(host.value, UiSlot.Context)),
            ready(docked, library),
        )
        fixture.calls.report(docked.call, setOf(PrMap))
        runCurrent()
        val expected = ServiceUiIntent.Internal.ResolveSlot(host, UiSlot.Context, true, docked, 3)
        assertEquals(listOf<ServiceUiIntent>(expected), fixture.machine.sent)
        assertTrue(fixture.drawings.isEmpty())
        assertTrue(fixture.model.surfaces.value.isEmpty())

        val disabled = library.copy(surfaces = listOf(summary.copy(isEnabled = false)), revision = 4)
        fixture.machine.state.value = ready(docked, disabled)
        runCurrent()
        assertEquals(expected.copy(isPresent = false, expectedLibraryRevision = 4), fixture.machine.sent.last())

        val changed = docked.copy(call = UiCallContext(preselected = mapOf("harness" to setOf("h_2"))))
        fixture.machine.state.value = ready(changed, library)
        runCurrent()
        val beforeReport = fixture.machine.sent.toList()
        fixture.calls.report(docked.call, emptySet())
        runCurrent()
        assertEquals(beforeReport, fixture.machine.sent)
        fixture.calls.report(changed.call, emptySet())
        runCurrent()
        assertEquals(expected.copy(isPresent = false, expectedContext = changed), fixture.machine.sent.last())
        assertTrue(fixture.drawings.isEmpty())
    }

    @Test
    fun `unmounting context closes its documents and revokes retained hosts before reopening`() = runTest {
        val summary = surface("summary", UiPlacement.Context).let {
            it.copy(content = it.content.copy(spec = """{"version":1,"body":{"type":"web","html":"<p>Context</p>"}}"""))
        }
        val web = ContextWeb()
        val fixture = Fixture(
            this,
            ServiceUiPlace.of(ServiceUiSlotRoute(host.value, UiSlot.Context)),
            ready(docked, UiLibrary(listOf(summary))),
            web,
        )
        fixture.calls.report(docked.call, setOf(PrMap))
        val place = fixture.subscribe()
        val oldDrawing = place.states.value.surfaces.single().key
        val original = fixture.drawings.single()
        val document = web.opened.single()
        assertTrue(original.first.host.isActive())
        assertFalse(document.isClosed)

        fixture.unsubscribe()
        assertTrue(fixture.model.surfaces.value.isEmpty())
        assertFalse(original.second.isActive)
        assertFalse(original.first.host.isActive())
        assertFalse(original.first.host.prompt("Hidden context", isSubmitted = false))
        assertTrue(document.isClosed)

        val reopened = fixture.subscribe()
        assertNotEquals(oldDrawing, reopened.states.value.surfaces.single().key)
        assertEquals(2, web.opened.size)
        assertFalse(web.opened.last().isClosed)
        assertFalse(original.first.host.isActive())
        assertFalse(original.first.host.prompt("Old drawing after reopening", isSubmitted = false))
        assertTrue(fixture.drawings.last().first.host.isActive())
    }

    @Test
    fun `unreadable context does not reserve space until its spec is repaired`() = runTest {
        val summary = surface("summary", UiPlacement.Context)
        val broken = summary.copy(content = summary.content.copy(spec = "{"))
        val fixture = Fixture(
            this,
            ServiceUiPlace.of(ServiceUiSlotRoute(host.value, UiSlot.Context)),
            ready(docked, UiLibrary(listOf(broken), revision = 2)),
        )
        fixture.calls.report(docked.call, setOf(PrMap))
        runCurrent()
        val expected = ServiceUiIntent.Internal.ResolveSlot(host, UiSlot.Context, false, docked, 2)
        assertEquals(listOf<ServiceUiIntent>(expected), fixture.machine.sent)
        assertTrue(fixture.drawings.isEmpty())

        fixture.machine.state.value = ready(docked, UiLibrary(listOf(summary), revision = 3))
        runCurrent()
        assertEquals(expected.copy(isPresent = true, expectedLibraryRevision = 3), fixture.machine.sent.last())
        assertTrue(fixture.drawings.isEmpty())
    }

    @Test
    fun `context actions and models are revoked immediately when the chat changes`() = runTest {
        val summary = surface("summary", UiPlacement.Context)
        val contextLibrary = UiLibrary(listOf(summary, map, details))
        val contextSlot = ServiceUiSlotRoute(host.value, UiSlot.Context)
        val fixture = Fixture(this, ServiceUiPlace.of(contextSlot), ready(docked, contextLibrary))
        fixture.calls.report(docked.call, setOf(PrMap))
        val place = fixture.subscribe()
        assertEquals(listOf("SUMMARY"), place.states.value.surfaces.map { it.title })
        val original = fixture.drawings.single()
        assertTrue(original.first.host.isActive())
        assertTrue(original.first.host.prompt("From context", isSubmitted = false))
        assertEquals(1, fixture.machine.sent.filterIsInstance<ServiceUiIntent.Internal.RequestPrompt>().size)

        val changed = docked.copy(call = UiCallContext(preselected = mapOf("harness" to setOf("h_2"))))
        fixture.machine.state.value = ready(changed, contextLibrary)
        assertFalse(original.first.host.isActive())
        assertFalse(original.first.host.prompt("Stale context", isSubmitted = true))
        assertFalse(original.first.host.openPanel("map", JsonObject(emptyMap())))
        runCurrent()
        assertEquals(PlaceState(), place.states.value)
        assertFalse(original.second.isActive)

        fixture.calls.report(changed.call, setOf(PrMap))
        runCurrent()
        assertEquals(listOf("SUMMARY"), place.states.value.surfaces.map { it.title })
        assertFalse(original.first.host.prompt("Still stale", isSubmitted = false))
        assertEquals(1, fixture.machine.sent.filterIsInstance<ServiceUiIntent.Internal.RequestPrompt>().size)
    }

    @Test
    fun `launchers open panels in the dock and modals over the place`() = runTest {
        val fixture = Fixture(this, ServiceUiPlace.of(slot), ready(docked))
        fixture.calls.report(docked.call, setOf(PrMap))
        val place = fixture.subscribe()

        place.intent(PlaceIntent.Launch(map.id.value))
        runCurrent()
        assertEquals(
            listOf<ServiceUiIntent>(ServiceUiIntent.Public.OpenPanel(host, map.id, expectedContext = docked)),
            fixture.machine.sent,
        )

        place.intent(PlaceIntent.Launch(details.id.value))
        runCurrent()
        val modal = assertIs<PlaceSurfaceUi>(place.states.value.modal)
        assertEquals("DETAILS", modal.title)
        assertTrue(modal.key in fixture.model.surfaces.value)

        place.intent(PlaceIntent.CloseModal)
        runCurrent()
        assertNull(place.states.value.modal)
        assertEquals(1, fixture.model.surfaces.value.size)
    }

    @Test
    fun `surfaces send prompts to their host and open panels and owner surfaces over a place without a dock`() =
        runTest {
            val bare = UiHostContext()
            val fixture = Fixture(this, ServiceUiPlace.of(slot), ready(bare))
            fixture.calls.report(bare.call, setOf(PrMap))
            val place = fixture.subscribe()
            val surface = fixture.subscribe(
                fixture.model.surfaces.value.getValue(place.states.value.surfaces.single().key).store,
            )

            surface.intent(SurfaceIntent.Press(button(surface, 1).press))
            runCurrent()
            assertEquals(
                listOf<ServiceUiIntent>(
                    ServiceUiIntent.Internal.RequestPrompt(
                        host,
                        "Rank PRs",
                        isSubmitted = true,
                        expectedContext = bare,
                    ),
                ),
                fixture.machine.sent,
            )

            surface.intent(SurfaceIntent.Press(button(surface, 2).press))
            runCurrent()
            assertEquals("MAP", place.states.value.modal?.title)

            surface.intent(SurfaceIntent.Press(button(surface, 3).press))
            runCurrent()
            assertEquals("DETAILS", place.states.value.modal?.title)
        }

    @Test
    fun `a modal closes for good when its surface owner or host leaves the place`() = runTest {
        val fixture = Fixture(this, ServiceUiPlace.of(slot), ready(docked))
        fixture.calls.report(docked.call, setOf(PrMap))
        val place = fixture.subscribe()

        place.intent(PlaceIntent.Launch(details.id.value))
        runCurrent()
        fixture.machine.state.value = ready(docked, UiLibrary(listOf(start, map, details.copy(isEnabled = false))))
        runCurrent()
        assertNull(place.states.value.modal)
        fixture.machine.state.value = ready(docked)
        runCurrent()
        assertNull(place.states.value.modal)

        place.intent(PlaceIntent.Launch(details.id.value))
        runCurrent()
        fixture.machine.state.value = ready(docked).copy(hosts = emptyMap())
        runCurrent()
        fixture.machine.state.value = ready(docked)
        runCurrent()
        assertNull(place.states.value.modal)
        assertEquals(listOf("START"), place.states.value.surfaces.map { it.title })
    }

    @Test
    fun `owners reported for an earlier context never show in a rebound place`() = runTest {
        val chat = UiHostContext(UiCallContext(preselected = mapOf("harness" to setOf("h_2"))), dock)
        val fixture = Fixture(this, ServiceUiPlace.of(slot), ready(docked))
        fixture.calls.report(docked.call, setOf(PrMap))
        val place = fixture.subscribe()
        val before = place.states.value
        assertEquals(listOf("START"), before.surfaces.map { it.title })

        place.intent(PlaceIntent.Launch(details.id.value))
        runCurrent()
        val previous = fixture.drawings.toList()
        assertTrue(previous.all { it.second.isActive })
        fixture.machine.state.value = ready(chat)
        runCurrent()
        assertEquals(PlaceState(), place.states.value)
        assertTrue(fixture.model.surfaces.value.isEmpty())
        assertTrue(previous.none { it.second.isActive })

        fixture.calls.report(chat.call, emptySet())
        runCurrent()
        assertEquals(PlaceState(), place.states.value)
    }

    @Test
    fun `retained actions cannot use a host that rebound before its owner snapshot arrived`() = runTest {
        for (original in listOf(docked, UiHostContext())) {
            val changed = original.copy(call = UiCallContext(preselected = mapOf("harness" to setOf("h_2"))))
            val fixture = Fixture(this, ServiceUiPlace.of(slot), ready(original))
            fixture.calls.report(original.call, setOf(PrMap))
            val place = fixture.subscribe()
            val oldHost = fixture.drawings.single().first.host

            // No scheduler advancement: the place still holds its old models, but the authoritative host changed.
            fixture.machine.state.value = ready(changed)
            assertFalse(oldHost.prompt("From the previous chat", isSubmitted = true))
            assertFalse(oldHost.openPanel("map", JsonObject(emptyMap())))
            assertFalse(oldHost.openSurface("details", JsonObject(emptyMap())))
            assertTrue(fixture.machine.sent.isEmpty())
            runCurrent()
            assertEquals(PlaceState(), place.states.value)

            // Even when the same owner is available again, a retained callback cannot revive its old drawing.
            fixture.calls.report(changed.call, setOf(PrMap))
            runCurrent()
            assertFalse(oldHost.prompt("Still stale", isSubmitted = false))
            assertFalse(oldHost.openSurface("details", JsonObject(emptyMap())))
            assertNull(place.states.value.modal)
            assertTrue(fixture.machine.sent.isEmpty())
        }
    }

    @Test
    fun `a panel draws the surface open in its dock and closes it`() = runTest {
        val open = ready(docked).copy(panels = mapOf(dock to UiPanel(map.id, host, sequence = 1)))
        val fixture = Fixture(this, ServiceUiPlace.of(ServiceUiPanelRoute(dock.value)), open)
        fixture.calls.report(docked.call, setOf(PrMap))
        val place = fixture.subscribe()

        assertEquals("MAP", place.states.value.panelTitle)
        assertEquals(listOf("MAP"), place.states.value.surfaces.map { it.title })

        place.intent(PlaceIntent.ClosePanel)
        runCurrent()
        assertEquals(listOf<ServiceUiIntent>(ServiceUiIntent.Public.ClosePanel(dock)), fixture.machine.sent)
    }

    @Test
    fun `a disabled feature a malformed place and an unreadable spec show nothing`() = runTest {
        val disabled = Fixture(this, ServiceUiPlace.of(slot), ServiceUiState.Disabled(hosts = mapOf(host to docked)))
        assertEquals(PlaceState(), disabled.subscribe().states.value)
        assertEquals(0, disabled.calls.subscriptions)

        val malformed = Fixture(this, ServiceUiPlace.of(ServiceUiSlotRoute("Not A Host", UiSlot.Start)), ready(docked))
        assertEquals(PlaceState(), malformed.subscribe().states.value)

        val broken = UiSurface(
            content = start.content.copy(spec = """{"version":9,"body":{"type":"divider"}}"""),
            createdAt = start.createdAt,
            updatedAt = start.updatedAt,
        )
        val unreadable = Fixture(this, ServiceUiPlace.of(slot), ready(docked, UiLibrary(listOf(broken))))
        unreadable.calls.report(docked.call, setOf(PrMap))
        val place = unreadable.subscribe()
        assertEquals(PlaceState(), place.states.value)
        assertEquals(emptySet(), unreadable.model.surfaces.value.keys)
    }

    @Test
    fun `the composer slot draws the quick actions of the app of its host and sends presses back to it`() = runTest {
        val composer = ServiceUiPlace.of(ServiceUiSlotRoute(host.value, UiSlot.Composer))
        val fixture = Fixture(this, composer, ready(docked))
        val authority = SurfaceInputBinding()
        val source = QuickSource(
            AppQuickActionsUi(persistentListOf(quick("next")), isAvailable = true, authority = authority),
        )
        fixture.board.attach(host, source)
        val place = fixture.subscribe()
        runCurrent()

        val shown = place.states.value.quickActions
        assertEquals(listOf("next"), shown.actions.map { it.id })
        assertFalse(place.states.value.isEmpty)
        val action = shown.actions.single()
        place.intent(PlaceIntent.RunQuickAction(action, shown.authority))
        place.intent(PlaceIntent.DismissQuickNotice(shown.authority))
        place.intent(PlaceIntent.RunQuickAction(action, authority = null))
        runCurrent()

        assertEquals(
            listOf<SurfaceIntent>(
                SurfaceIntent.Bound(authority, SurfaceIntent.Press(action.press)),
                SurfaceIntent.Bound(authority, SurfaceIntent.DismissNotice),
            ),
            source.sent,
        )

        source.state.value = AppQuickActionsUi()
        runCurrent()
        assertEquals(PlaceState(), place.states.value)
        assertTrue(place.states.value.isEmpty)
    }

    @Test
    fun `a notice alone keeps the composer slot visible until it is dismissed`() = runTest {
        val composer = ServiceUiPlace.of(ServiceUiSlotRoute(host.value, UiSlot.Composer))
        val fixture = Fixture(this, composer, ready(docked))
        val source = QuickSource(AppQuickActionsUi(notice = NoticeUi.Unsupported, authority = SurfaceInputBinding()))
        fixture.board.attach(host, source)
        val place = fixture.subscribe()
        runCurrent()

        assertFalse(place.states.value.isEmpty)
        assertEquals(NoticeUi.Unsupported, place.states.value.quickActions.notice)
    }

    @Test
    fun `slots other than the composer and panels ignore the app's quick actions`() = runTest {
        val authority = SurfaceInputBinding()
        for (route in listOf(UiSlot.Start, UiSlot.Context)) {
            val fixture = Fixture(this, ServiceUiPlace.of(ServiceUiSlotRoute(host.value, route)), ready(docked))
            val source = QuickSource(AppQuickActionsUi(persistentListOf(quick("next")), authority = authority))
            fixture.board.attach(host, source)
            val place = fixture.subscribe()
            runCurrent()
            val action = quick("next")

            place.intent(PlaceIntent.RunQuickAction(action, authority))
            runCurrent()

            assertEquals(AppQuickActionsUi(), place.states.value.quickActions, route.name)
            assertEquals(emptyList(), source.sent, route.name)
        }
        val panel = Fixture(this, ServiceUiPlace.of(ServiceUiPanelRoute(dock.value)), ready(docked))
        val source = QuickSource(AppQuickActionsUi(persistentListOf(quick("next")), authority = authority))
        panel.board.attach(host, source)
        assertEquals(AppQuickActionsUi(), panel.subscribe().states.value.quickActions)
    }

    private fun quick(id: String) = QuickActionUi(
        id = id,
        label = id,
        style = ButtonStyleUi.Secondary,
        icon = null,
        isEnabled = true,
        isBusy = false,
        confirm = null,
        press = UiPress("q.$id", UiAction.Close, UiScope()),
    )

    private class QuickSource(initial: AppQuickActionsUi) : AppQuickActionsSource {
        override val state = MutableStateFlow(initial)
        val sent = mutableListOf<SurfaceIntent>()

        override fun send(intent: SurfaceIntent) {
            sent += intent
        }
    }

    private fun ready(context: UiHostContext, library: UiLibrary = this.library) =
        ServiceUiState.Ready(hosts = mapOf(host to context), library = library)

    private fun button(surface: Provider<SurfaceState, *, *>, index: Int): UiNodeUi.Button =
        assertIs<UiNodeUi.Button>(assertIs<UiNodeUi.Column>(surface.states.value.root).children[index])

    private class Fixture(
        private val scope: TestScope,
        place: ServiceUiPlace,
        state: ServiceUiState,
        web: UiWebDocuments = UiWebDocuments.Off,
    ) {
        val machine = FakeMachine(state)
        val calls = FakeCalls()
        val board = ProfileAppQuickActionBoard()
        val drawings = mutableListOf<Pair<SurfaceSession, CoroutineScope>>()
        private val subscriptions = mutableListOf<Job>()
        private val stores = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler)))
        val model = PlaceModel(
            place,
            machine,
            calls,
            object : UiCardStorage {
                override suspend fun latest(chat: SessionRef, surface: UiSurfaceId): UiCard? = null
                override suspend fun add(card: UiCard): UiCardAdded = error("unused")
                override fun observe(id: String): Flow<UiCard?> = flowOf(null)
            },
            { session, child ->
                drawings += session to child
                SurfaceModel(
                    session,
                    calls,
                    UnsupportedUiPlatform,
                    stores,
                    child,
                    web,
                )
            },
            board,
            Desktop,
            stores,
            TestScopeHandle(scope.backgroundScope),
        )

        suspend fun subscribe(): Provider<PlaceState, PlaceIntent, PlaceAction> = subscribe(model.store)

        fun unsubscribe() {
            subscriptions.forEach { it.cancel() }
            subscriptions.clear()
            scope.runCurrent()
        }

        suspend fun <S : MVIState, I : MVIIntent, A : MVIAction> subscribe(store: Store<S, I, A>): Provider<S, I, A> {
            val provider = CompletableDeferred<Provider<S, I, A>>()
            subscriptions += scope.backgroundScope.launch {
                store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }

    private class ContextWeb : UiWebDocuments {
        override val configuration: Flow<UiWebConfiguration> = flowOf(UiWebConfiguration(isEnabled = true))
        val opened = mutableListOf<ContextDocument>()

        override fun open(document: BrowserDocument): BrowserDocumentHandle =
            ContextDocument(BrowserDocumentRoute("context-${opened.size}")).also { opened += it }
    }

    private class ContextDocument(override val route: BrowserDocumentRoute) : BrowserDocumentHandle {
        val updates = mutableListOf<JsonElement?>()
        var isClosed = false

        override fun update(data: JsonElement?) {
            updates += data
        }

        override fun close() {
            isClosed = true
        }
    }

    private class FakeMachine(initial: ServiceUiState) : Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput> {
        override val name = "service_ui"
        override val state = MutableStateFlow(initial)
        override val outputs: Flow<ServiceUiOutput> = emptyFlow()
        val sent = mutableListOf<ServiceUiIntent>()

        override suspend fun send(intent: ServiceUiIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    /** Active owners reported per context; a context nobody reported for yet stays silent. */
    private class FakeCalls : UiMethodCalls {
        private val reports = mutableMapOf<UiCallContext, MutableSharedFlow<Set<UiOwner>>>()
        var subscriptions = 0

        fun report(context: UiCallContext, owners: Set<UiOwner>) {
            flow(context).tryEmit(owners)
        }

        private fun flow(context: UiCallContext) = reports.getOrPut(context) { MutableSharedFlow(replay = 1) }

        override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> =
            flow(context).onSubscription { subscriptions++ }

        override fun methods(owner: UiOwner): Flow<UiMethods> = flowOf(UiMethods(isAvailable = true))

        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult = UiMethodResult.Success(JsonObject(emptyMap()))
    }

    private object Desktop : PlatformInfo {
        override val host: HostPlatform = HostPlatform.MacOs
    }

    private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "test-place"
        override val savedState: ScopeSavedState get() = error("unused")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }
}
