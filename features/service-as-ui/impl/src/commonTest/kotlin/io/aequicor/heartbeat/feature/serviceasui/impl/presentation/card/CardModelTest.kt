package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiCardRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.impl.data.UnsupportedUiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.surface
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertIs
import kotlin.time.Instant

class CardModelTest {
    private val chat = SessionRef(EngineId("pi"), SessionSourceId("s"), "chat")
    private val host = UiHostId("pane-1")
    private val bound = UiHostContext(UiCallContext(session = chat))
    private val map = surface("map", UiPlacement.Panel)

    @Test
    fun `an inline card draws its spec and inserts prompts into the composer of its chat`() = runTest {
        val fixture = Fixture(this, ready())
        fixture.cards.value = mapOf(ID to card(UiCardSource.Inline(ASK)))
        val card = fixture.subscribe()

        assertEquals(CardPhase.Drawn, card.states.value.phase)
        assertEquals("PRs", card.states.value.title)
        val surface = fixture.subscribe(fixture.model.surfaces.value.getValue(card.states.value.key!!).store)
        val button = assertIs<UiNodeUi.Button>(surface.states.value.root)
        surface.intent(SurfaceIntent.Press(button.press))
        runCurrent()
        assertEquals(
            listOf<ServiceUiIntent>(
                ServiceUiIntent.Internal.RequestPrompt(host, "Rank PRs", isSubmitted = false, expectedContext = bound),
            ),
            fixture.machine.sent,
        )
    }

    @Test
    fun `a card prompts only into its own place while that place shows its chat`() = runTest {
        val elsewhere = ServiceUiState.Ready(
            hosts = mapOf(host to bound, UiHostId("pane-2") to UiHostContext(UiCallContext(session = chat))),
        )
        val second = Fixture(this, elsewhere, "pane-2")
        second.press()
        assertEquals(UiHostId("pane-2"), (second.machine.sent.single() as ServiceUiIntent.Internal.RequestPrompt).host)

        val other = ServiceUiState.Ready(
            hosts = mapOf(host to UiHostContext(UiCallContext(session = chat.copy(nativeId = "other")))),
        )
        val fixture = Fixture(this, other)
        fixture.press()
        assertEquals(emptyList(), fixture.machine.sent, "a place showing another chat takes no prompt")
    }

    @Test
    fun `a card panel request carries the context of its drawing`() = runTest {
        val docked = bound.copy(dock = UiDockId("side"))
        val fixture = Fixture(this, ready(UiLibrary(listOf(map))).copy(hosts = mapOf(host to docked)))
        fixture.cards.value = mapOf(ID to card(UiCardSource.Surface(map.id)))
        val card = fixture.subscribe()
        val surface = fixture.subscribe(fixture.model.surfaces.value.getValue(card.states.value.key!!).store)
        val root = assertIs<UiNodeUi.Column>(surface.states.value.root)
        val button = assertIs<UiNodeUi.Button>(root.children[2])
        surface.intent(SurfaceIntent.Press(button.press))
        runCurrent()
        assertEquals(
            listOf<ServiceUiIntent>(ServiceUiIntent.Public.OpenPanel(host, map.id, expectedContext = docked)),
            fixture.machine.sent,
        )
    }

    @Test
    fun `a missing card shows a placeholder until it is stored`() = runTest {
        val fixture = Fixture(this, ready())
        val card = fixture.subscribe()
        assertEquals(CardPhase.Missing, card.states.value.phase)

        fixture.cards.value = mapOf(ID to card(UiCardSource.Inline(ASK)))
        runCurrent()
        assertEquals(CardPhase.Drawn, card.states.value.phase)

        fixture.cards.value = emptyMap()
        runCurrent()
        assertEquals(CardState(CardPhase.Missing), card.states.value)
        assertEquals(emptySet(), fixture.model.surfaces.value.keys)
    }

    @Test
    fun `a library card follows its surface and the feature`() = runTest {
        val fixture = Fixture(this, ready(UiLibrary(listOf(map))))
        fixture.cards.value = mapOf(ID to card(UiCardSource.Surface(map.id)))
        val card = fixture.subscribe()
        assertEquals(CardPhase.Drawn, card.states.value.phase)

        fixture.machine.state.value = ready(UiLibrary(listOf(map.copy(isEnabled = false))))
        runCurrent()
        assertEquals(CardState(CardPhase.Unavailable, "PRs"), card.states.value)

        fixture.machine.state.value = ServiceUiState.Loading(hosts = mapOf(host to bound))
        runCurrent()
        assertEquals(CardPhase.Loading, card.states.value.phase)

        fixture.machine.state.value = ServiceUiState.Disabled(hosts = mapOf(host to bound))
        runCurrent()
        assertEquals(CardPhase.Unavailable, card.states.value.phase)
    }

    private fun ready(library: UiLibrary = UiLibrary()) =
        ServiceUiState.Ready(hosts = mapOf(host to bound), library = library)

    private fun card(source: UiCardSource) = UiCard(
        id = ID,
        chat = chat,
        callId = "call-1",
        title = "PRs",
        source = source,
        args = JsonObject(emptyMap()),
        createdAt = Instant.fromEpochSeconds(0),
    )

    private class Fixture(private val scope: TestScope, state: ServiceUiState, place: String? = "pane-1") {
        val machine = FakeMachine(state)
        val cards = MutableStateFlow<Map<String, UiCard>>(emptyMap())
        var inlineCard: UiCard = UiCard(
            id = ID,
            chat = SessionRef(EngineId("pi"), SessionSourceId("s"), "chat"),
            callId = "call-1",
            title = "PRs",
            source = UiCardSource.Inline(ASK),
            args = JsonObject(emptyMap()),
            createdAt = Instant.fromEpochSeconds(0),
        )
        private val stores = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler)))
        private val storage = object : UiCardStorage {
            override suspend fun add(card: UiCard): UiCardAdded = error("unused")
            override fun observe(id: String): Flow<UiCard?> = cards.map { it[id] }
        }
        val model = CardModel(
            ServiceUiCardRoute(ID, place),
            storage,
            machine,
            { session, child ->
                SurfaceModel(
                    session,
                    NoCalls,
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

        suspend fun subscribe(): Provider<CardState, CardIntent, CardAction> = subscribe(model.store)

        /** Stores an inline card asking for a prompt and presses its button. */
        suspend fun press() {
            cards.value = mapOf(ID to inlineCard)
            val card = subscribe()
            val surface = subscribe(model.surfaces.value.getValue(card.states.value.key!!).store)
            surface.intent(SurfaceIntent.Press(assertIs<UiNodeUi.Button>(surface.states.value.root).press))
            scope.runCurrent()
        }

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

    private object NoCalls : UiMethodCalls {
        override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = flowOf(setOf(PrMap))
        override fun methods(owner: UiOwner): Flow<UiMethods> = flowOf(UiMethods(isAvailable = true))
        override suspend fun call(
            owner: UiOwner,
            method: String,
            arguments: JsonObject,
            context: UiCallContext,
        ): UiMethodResult = error("Cards must not call by themselves")
    }

    private object Desktop : PlatformInfo {
        override val host: HostPlatform = HostPlatform.MacOs
    }

    private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name = "test-card"
        override val savedState: ScopeSavedState get() = error("unused")
        override val isClosed = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }

    private companion object {
        const val ID = "c_1"
        const val ASK = """{"version":1,"body":{"type":"button","label":"Ask",
            "action":{"type":"prompt","text":"Rank PRs","submit":true}}}"""
    }
}
