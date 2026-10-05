package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SessionObservationTest {
    @Test
    fun `observation follows turns context closure and replacement without owning the handle`() = runTest {
        val registry = ActiveSessionRegistry()
        val ref = sessionRef("cell")
        var latest: SessionObservationSnapshot? = null
        val reader = backgroundScope.launch { registry.observe(ref).collect { latest = it } }
        runCurrent()
        assertNull(latest)
        val native = FakeNativeSession(ref)
        val usage = object : SessionContextUsage {
            override val state = MutableStateFlow<ContextUsage?>(ContextUsage(40, 100))
        }
        val handle = object : ActiveSession by native {
            override val features = FeatureTable(mapOf(SessionContextUsage.id to available(usage)))
        }
        registry.add(handle)
        runCurrent()
        assertIs<ActiveSessionState.Ready>(latest?.state)
        assertEquals(ContextUsage(40, 100), latest?.context)

        native.native.value = ActiveSessionState.Running(Turn(TurnId("turn"), null, TestTarget))
        usage.state.value = ContextUsage(65, 100)
        runCurrent()
        assertIs<ActiveSessionState.Running>(latest?.state)
        assertEquals(ContextUsage(65, 100), latest?.context)

        native.native.value = ActiveSessionState.Ready()
        runCurrent()
        assertIs<ActiveSessionState.Ready>(latest?.state)
        assertEquals(ContextUsage(65, 100), latest?.context)
        native.native.value = ActiveSessionState.Closing()
        runCurrent()
        assertNull(latest)
        assertEquals(0, usage.state.subscriptionCount.value)
        registry.remove(handle)
        registry.add(FakeNativeSession(ref))
        runCurrent()
        assertIs<ActiveSessionState.Ready>(latest?.state)
        assertNull(latest?.context)
        reader.cancel()
        runCurrent()
        assertEquals(0, native.closes)
        assertEquals(emptyList(), native.sent)
        assertEquals(emptyList(), native.cancelled)
    }

    @Test
    fun `observation ignores identical native ids from other engines and sources`() = runTest {
        val registry = ActiveSessionRegistry()
        val ref = sessionRef("cell")
        val observed = mutableListOf<SessionObservationSnapshot?>()
        backgroundScope.launch { registry.observe(ref).collect { observed += it } }
        registry.add(FakeNativeSession(ref.copy(engine = EngineId("other"))))
        registry.add(FakeNativeSession(ref.copy(source = SessionSourceId("other"))))
        runCurrent()
        assertEquals(listOf<SessionObservationSnapshot?>(null), observed)
    }
}
