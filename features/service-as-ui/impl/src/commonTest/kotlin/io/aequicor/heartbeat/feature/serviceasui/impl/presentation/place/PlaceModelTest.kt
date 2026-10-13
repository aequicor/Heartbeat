package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
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
import io.aequicor.heartbeat.feature.serviceasui.impl.data.UnsupportedUiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceSession
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
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

    private fun ready(context: UiHostContext, library: UiLibrary = this.library) =
        ServiceUiState.Ready(hosts = mapOf(host to context), library = library)

    private fun button(surface: Provider<SurfaceState, *, *>, index: Int): UiNodeUi.Button =
        assertIs<UiNodeUi.Button>(assertIs<UiNodeUi.Column>(surface.states.value.root).children[index])

    private class Fixture(private val scope: TestScope, place: ServiceUiPlace, state: ServiceUiState) {
        val machine = FakeMachine(state)
        val calls = FakeCalls()
        val drawings = mutableListOf<Pair<SurfaceSession, CoroutineScope>>()
        private val stores = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler)))
        val model = PlaceModel(
            place,
            machine,
            calls,
            { session, child ->
                drawings += session to child
                SurfaceModel(
                    session,
                    calls,
                    UnsupportedUiPlatform,
                    stores,
                    child,
                    UiWebDocuments.Off,
                )
            },
            Desktop,
            stores,
            TestScopeHandle(scope.backgroundScope),
        )

        suspend fun subscribe(): Provider<PlaceState, PlaceIntent, PlaceAction> = subscribe(model.store)

        suspend fun <S : MVIState, I : MVIIntent, A : MVIAction> subscribe(store: Store<S, I, A>): Provider<S, I, A> {
            val provider = CompletableDeferred<Provider<S, I, A>>()
            scope.backgroundScope.launch {
                store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
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
