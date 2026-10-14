package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiApp
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SurfaceCardFocusTest {
    private val owner = UiOwner("harness", "fitness")
    private val host = UiHostId("studio.pane-0")
    private val chat = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")
    private val context = UiHostContext(UiCallContext(session = chat))
    private val surface = UiSurfaceId("harness:fitness:workout")
    private val card = UiCard(
        "target",
        chat,
        null,
        "Workout",
        UiCardSource.Surface(surface),
        JsonObject(emptyMap()),
        Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `focus resolves a local surface against the bound chat before requesting scroll`() = runTest {
        val machine = FakeMachine()
        val cards = Cards(card)
        assertTrue(machine.focusSurfaceCard(cards, host, owner, "workout", context))
        assertEquals(listOf(chat to surface), cards.lookups)
        assertEquals<List<ServiceUiIntent>>(
            listOf(ServiceUiIntent.Internal.RequestFocusCard(host, surface, owner, "target", context)),
            machine.sent,
        )
    }

    @Test
    fun `anonymous unbound and foreign surface names cannot look up cards`() = runTest {
        val machine = FakeMachine()
        val cards = Cards(card)
        assertFalse(machine.focusSurfaceCard(cards, host, null, "workout", context))
        assertFalse(machine.focusSurfaceCard(cards, null, owner, "workout", context))
        assertFalse(machine.focusSurfaceCard(cards, host, owner, "harness:other:workout", context))
        assertFalse(machine.focusSurfaceCard(cards, host, owner, "workout", UiHostContext()))
        assertTrue(cards.lookups.isEmpty())
        assertTrue(machine.sent.isEmpty())
    }

    @Test
    fun `missing card and rejected stale context do not report successful focus`() = runTest {
        val machine = FakeMachine()
        assertFalse(machine.focusSurfaceCard(Cards(null), host, owner, "workout", context))
        assertTrue(machine.sent.isEmpty())
        machine.result = SendResult.Ignored
        assertFalse(machine.focusSurfaceCard(Cards(card), host, owner, "workout", context))
    }

    @Test
    fun `focus of the selected app opens it before the first chat without a card lookup`() = runTest {
        val machine = FakeMachine()
        val cards = Cards(null)
        val start = UiHostContext(UiCallContext(preselected = mapOf(owner.kind to setOf(owner.id))))
        machine.state.value = ServiceUiState.Ready(
            hosts = mapOf(host to start),
            apps = mapOf(host to UiApp(surface, start)),
        )
        assertTrue(machine.focusSurfaceCard(cards, host, owner, "workout", start))
        assertEquals(listOf<ServiceUiIntent>(ServiceUiIntent.Public.OpenApp(host, surface, start)), machine.sent)
        assertTrue(cards.lookups.isEmpty())
        assertFalse(machine.focusSurfaceCard(cards, host, owner, "workout", UiHostContext()))
    }

    private class Cards(private val card: UiCard?) : UiCardStorage {
        val lookups = mutableListOf<Pair<SessionRef, UiSurfaceId>>()
        override suspend fun add(card: UiCard): UiCardAdded = error("unused")
        override fun observe(id: String) = flowOf(card)
        override suspend fun latest(chat: SessionRef, surface: UiSurfaceId): UiCard? {
            lookups += chat to surface
            return card
        }
    }

    private class FakeMachine : Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput> {
        override val name = "test"
        override val state = MutableStateFlow<ServiceUiState>(ServiceUiState.Ready())
        override val outputs = MutableSharedFlow<ServiceUiOutput>()
        val sent = mutableListOf<ServiceUiIntent>()
        var result = SendResult.Accepted
        override suspend fun send(intent: ServiceUiIntent): SendResult {
            sent += intent
            return result
        }
    }
}
