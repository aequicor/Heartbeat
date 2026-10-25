package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.ReleaseOutcome
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FacadeCellReleaseTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, null, null)

    @Test
    fun `a failed cancellation of a late opener retains ownership for another abort`() = runTest {
        val running = Turn(TurnId("native"), RequestId("r0"), TARGET)
        val stored = FakeSession(session("stored")).apply {
            resumedAs = ActiveSessionState.Running(running)
            cancelFailure = IllegalStateException("cancel failed")
        }
        val facade = FakeFacade(stored = mapOf(stored.ref to stored))
        val cells = cellSessions(facade)
        facade.onResume = { cells.release(key, stored.ref, ReleaseMode.Lyse) }
        assertFailsWith<EngineException> { cells.open(key, route, stored.ref) }
        assertEquals(0, stored.closes)
        assertFalse(stored.isArchived)
        assertTrue(cells.hasPendingRelease(ORGANISM))

        stored.cancelFailure = null
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, stored.ref, ReleaseMode.Lyse))
        assertEquals(1, stored.closes)
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `recovery reuses the handle of a developing cell whose sleep stop was unconfirmed`() = runTest {
        val native = FakeSession(session("new")).apply {
            finish = null
            ignoresCancel = true
        }
        val facade = FakeFacade(ArrayDeque(listOf(native)))
        val cells = cellSessions(facade)
        val turn = cells.open(key, route, null, generation = 1).submit(RequestId("r1"), "work", null)
        cells.releaseAll()
        val recovered = cells.open(key, route, native.ref, generation = 2)
        assertEquals(turn, recovered.activeTurn())
        assertTrue(facade.resumptions.isEmpty())
        native.ignoresCancel = false
        cells.releaseAll()
        assertEquals(1, native.closes)
    }

    @Test
    fun `an opener spanning sleep stays pending until its recovered turn stops`() = runTest {
        val stored = FakeSession(session("stored")).apply {
            resumedAs = ActiveSessionState.Running(Turn(TurnId("native"), RequestId("r0"), TARGET))
            ignoresCancel = true
        }
        val facade = FakeFacade(stored = mapOf(stored.ref to stored))
        val cells = cellSessions(facade)
        facade.onResume = {
            cells.releaseAll()
            assertTrue(cells.hasPendingRelease(ORGANISM))
        }
        assertFailsWith<EngineException> { cells.open(key, route, stored.ref) }
        assertEquals(0, stored.closes)
        assertTrue(cells.hasPendingRelease(ORGANISM))
        stored.ignoresCancel = false
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, stored.ref, ReleaseMode.Lyse))
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `sleep retains unconfirmed handles until a later stop succeeds`() = runTest {
        val native = FakeSession(session("new")).apply {
            finish = null
            ignoresCancel = true
        }
        val cells = cellSessions(FakeFacade(ArrayDeque(listOf(native))))
        cells.open(key, route, null).submit(RequestId("r1"), "work", null)
        assertEquals(ReleaseOutcome.Unconfirmed, cells.release(key, native.ref, ReleaseMode.Lyse))
        cells.releaseAll()
        assertEquals(0, native.closes)
        assertTrue(cells.hasPendingRelease(ORGANISM))
        native.ignoresCancel = false
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, native.ref, ReleaseMode.Lyse))
        assertEquals(1, native.closes)
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `a cell moved to another engine gets a new session and its old one is closed`() = runTest {
        val own = FakeSession(SessionRef(EngineId("koog"), SessionSourceId("local"), "own"))
        val fallback = FakeSession(session("fallback"))
        val facade = FakeFacade(ArrayDeque(listOf(own, fallback)))
        val harnesses = FakeCellHarnesses()
        val cells = FacadeCellSessions(facade, harnesses)
        val ownRoute = route.copy(target = TARGET.copy(engine = EngineId("koog")), harnesses = setOf("h"))
        cells.open(key, ownRoute, existing = null, generation = 1)
        val moved = cells.open(key, route.copy(harnesses = setOf("h")), existing = null, generation = 2)
        assertEquals(fallback.ref, moved.session)
        assertEquals(2, facade.creations.size)
        assertEquals(1, own.closes)
        // The organism forgot the replaced session: it is released like a dead cell's, harness slots included.
        assertTrue(own.isArchived)
        assertFalse(fallback.isArchived)
        assertEquals(listOf(own.ref to setOf("h")), harnesses.disconnected)
    }

    @Test
    fun `a cell moved to another model on the same engine and binding gets a new session`() = runTest {
        val own = FakeSession(session("own"))
        val fallback = FakeSession(session("fallback"))
        val facade = FakeFacade(ArrayDeque(listOf(own, fallback)))
        val cells = cellSessions(facade)
        val ownRoute = route.copy(target = TARGET.copy(model = ModelId("cheap")))
        cells.open(key, ownRoute, existing = null, generation = 1)
        assertEquals(own.ref, cells.open(key, ownRoute, existing = null, generation = 1).session)
        val moved = cells.open(key, route, existing = null, generation = 2)
        assertEquals(fallback.ref, moved.session)
        assertEquals(2, facade.creations.size)
        assertEquals(1, own.closes)
    }

    @Test
    fun `an unconfirmed replaced session is archived and loses its harnesses once a lysis stops it`() = runTest {
        val own = FakeSession(SessionRef(EngineId("koog"), SessionSourceId("local"), "own")).apply {
            finish = null
            ignoresCancel = true
        }
        val fallback = FakeSession(session("fallback"))
        val harnesses = FakeCellHarnesses()
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(own, fallback))), harnesses)
        val ownRoute = route.copy(target = TARGET.copy(engine = EngineId("koog")), harnesses = setOf("h"))
        cells.open(key, ownRoute, existing = null, generation = 1).submit(RequestId("r1"), "work", null)
        cells.open(key, route.copy(harnesses = setOf("h")), existing = null, generation = 2)
        assertEquals(0, own.closes)
        assertFalse(own.isArchived)
        assertTrue(harnesses.disconnected.isEmpty())

        own.ignoresCancel = false
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, fallback.ref, ReleaseMode.Lyse))
        assertEquals(1, own.closes)
        assertTrue(own.isArchived)
        assertEquals(listOf(own.ref to setOf("h")), harnesses.disconnected)
    }

    @Test
    fun `a failed harness removal of a replaced session is retried at the cell's lysis`() = runTest {
        val own = FakeSession(SessionRef(EngineId("koog"), SessionSourceId("local"), "own"))
        val fallback = FakeSession(session("fallback"))
        val harnesses = FakeCellHarnesses().apply { failures = 1 }
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(own, fallback))), harnesses)
        val ownRoute = route.copy(target = TARGET.copy(engine = EngineId("koog")), harnesses = setOf("h"))
        cells.open(key, ownRoute, existing = null, generation = 1)
        cells.open(key, route.copy(harnesses = setOf("h")), existing = null, generation = 2)
        assertEquals(1, own.closes)
        assertTrue(harnesses.disconnected.isEmpty())

        cells.release(key, fallback.ref, ReleaseMode.Lyse)
        assertEquals(listOf(own.ref to setOf("h")), harnesses.disconnected)
    }

    @Test
    fun `a replaced session that is still sending is not stopped until the cell is released`() = runTest {
        val own = FakeSession(SessionRef(EngineId("koog"), SessionSourceId("local"), "own"))
        val fallback = FakeSession(session("fallback"))
        val harnesses = FakeCellHarnesses()
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(own, fallback))), harnesses)
        val ownRoute = route.copy(target = TARGET.copy(engine = EngineId("koog")), harnesses = setOf("h"))
        val handle = cells.open(key, ownRoute, existing = null, generation = 1)
        val sent = CompletableDeferred<Unit>()
        own.beforeSend = { sent.await() }
        val sending = async { handle.submit(RequestId("r1"), "work", null) }
        runCurrent()
        assertEquals(fallback.ref, cells.open(key, route.copy(harnesses = setOf("h")), null, generation = 2).session)
        assertEquals(0, own.closes)
        assertFalse(own.isArchived)
        assertTrue(own.cancelled.isEmpty())

        sent.complete(Unit)
        sending.await()
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, fallback.ref, ReleaseMode.Lyse))
        assertEquals(1, own.closes)
        assertTrue(own.isArchived)
        assertEquals(listOf(own.ref to setOf("h")), harnesses.disconnected)
    }
}
