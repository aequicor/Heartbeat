package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActiveSessionRegistryTest {
    @Test
    fun `lock is dropped after exclusive block`() = runTest {
        val registry = ActiveSessionRegistry()
        repeat(3) { index -> registry.exclusive(sessionRef("s$index")) { } }

        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `lock is dropped after failed block`() = runTest {
        val registry = ActiveSessionRegistry()
        assertFailsWith<IllegalStateException> {
            registry.exclusive(sessionRef("s")) { error("boom") }
        }

        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `lock is kept while waiter pending and serializes`() = runTest {
        val registry = ActiveSessionRegistry()
        val ref = sessionRef("s")
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch {
            registry.exclusive(ref) {
                order += "first"
                release.await()
                order += "first-done"
            }
        }
        runCurrent()
        val second = launch { registry.exclusive(ref) { order += "second" } }
        runCurrent()

        assertEquals(1, registry.lockCount())
        assertEquals(listOf("first"), order)

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("first", "first-done", "second"), order)
        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `lock is dropped when waiter is cancelled`() = runTest {
        val registry = ActiveSessionRegistry()
        val ref = sessionRef("s")
        val release = CompletableDeferred<Unit>()
        val holder = launch { registry.exclusive(ref) { release.await() } }
        runCurrent()
        val waiter = launch { registry.exclusive(ref) { } }
        runCurrent()
        waiter.cancel()
        release.complete(Unit)
        holder.join()
        waiter.join()

        assertEquals(0, registry.lockCount())
    }

    @Test
    fun `summary counts open handles and turns in flight per engine`() = runTest {
        val registry = ActiveSessionRegistry()
        val running = CountedHandle(TestEngine, ActiveSessionState.Running(CountedTurn))
        registry.add(CountedHandle(TestEngine, ActiveSessionState.Ready()))
        registry.add(running)
        registry.add(CountedHandle(OtherEngine, ActiveSessionState.Ready()))

        assertEquals(
            mapOf(TestEngine to SessionCounts(open = 2, activeTurns = 1), OtherEngine to SessionCounts(1, 0)),
            registry.summary.first(),
        )

        running.close()
        assertEquals(SessionCounts(open = 1, activeTurns = 0), registry.summary.first()[TestEngine])
        registry.remove(running)
        assertEquals(SessionCounts(open = 1, activeTurns = 0), registry.summary.first()[TestEngine])
    }

    @Test
    fun `summary is empty without open handles`() = runTest {
        assertEquals(emptyMap(), ActiveSessionRegistry().summary.first())
    }
}

private val OtherEngine = EngineId("other")
private val CountedTurn = Turn(TurnId("turn"), null, EngineTarget(TestEngine, EngineBindingId("b"), ModelId("m")))

private class CountedHandle(engine: EngineId, initial: ActiveSessionState) : ActiveSession {
    override val ref = sessionRef("counted")
    override val route = ExecutionRoute(engine, EngineBindingId("b"), AuthSourceId("s"), AuthRevision.Known("r1"))
    override val state = MutableStateFlow(initial)
    override val features: EngineFeatures = NoEngineFeatures

    override suspend fun close() {
        state.value = ActiveSessionState.Closed
    }
}
