package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.domain.ReleaseOutcome
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FacadeCellRecoveryOwnershipTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, null, null)
    private val running = Turn(TurnId("native"), RequestId("request"), TARGET)

    @Test
    fun `recovery adopts an unavailable late opener and retirement closes it without another attach`() = runTest {
        val old = FakeSession(session("stored")).apply {
            resumedAs = ActiveSessionState.Unavailable(EngineFailure.Unknown(), running)
        }
        val unused = FakeSession(old.ref)
        val facade = FakeFacade(stored = mapOf(old.ref to old))
        val cells = FacadeCellSessions(facade)
        facade.onResume = { cells.releaseAll() }
        assertFailsWith<EngineException> { cells.open(key, route, old.ref, generation = 1) }
        facade.onResume = {}
        facade.resumedHandles += unused
        val recovered = cells.open(key, route, old.ref, generation = 2)
        assertEquals(running.id, recovered.activeTurn())
        assertEquals(1, facade.resumptions.size)
        old.end(running.id, TurnOutcome.Completed)
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, old.ref, ReleaseMode.Retire, generation = 2))
        assertEquals(1, old.closes)
        assertEquals(0, unused.closes)
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `a stale unavailable opener finishing after recovery retirement cannot retain a duplicate`() = runTest {
        val proceed = CompletableDeferred<Unit>()
        val old = FakeSession(session("stored")).apply {
            resumedAs = ActiveSessionState.Running(running)
            beforeCancel = { proceed.await() }
        }
        val recovered = FakeSession(old.ref).apply { resumedAs = ActiveSessionState.Running(running) }
        val facade = FakeFacade(stored = mapOf(old.ref to old))
        val cells = FacadeCellSessions(facade)
        facade.onResume = { cells.releaseAll() }
        val opening = launch {
            assertFailsWith<EngineException> { cells.open(key, route, old.ref, generation = 1) }
        }
        runCurrent()
        facade.onResume = {}
        facade.resumedHandles += recovered
        cells.open(key, route, old.ref, generation = 2)
        recovered.end(running.id, TurnOutcome.Completed)
        cells.release(key, old.ref, ReleaseMode.Retire, generation = 2)
        old.state.value = ActiveSessionState.Unavailable(EngineFailure.Unknown(), running)
        proceed.complete(Unit)
        opening.join()
        assertEquals(1, old.closes)
        assertEquals(1, recovered.closes)
        assertTrue(recovered.cancelled.isEmpty())
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `retirement of an earlier generation cannot discard a new unconfirmed opener`() = runTest {
        val first = FakeSession(session("stored"))
        val second = FakeSession(first.ref).apply {
            resumedAs = ActiveSessionState.Running(running)
            ignoresCancel = true
        }
        val facade = FakeFacade(ArrayDeque(listOf(first)))
        val cells = FacadeCellSessions(facade)
        cells.open(key, route, null, generation = 1)
        cells.release(key, first.ref, ReleaseMode.Retire, generation = 1)
        facade.resumedHandles += second
        facade.onResume = { cells.releaseAll() }
        assertFailsWith<EngineException> { cells.open(key, route, first.ref, generation = 2) }
        assertEquals(0, second.closes)
        assertTrue(cells.hasPendingRelease(ORGANISM))
        second.ignoresCancel = false
        cells.release(key, first.ref, ReleaseMode.Lyse, generation = 2)
        assertEquals(1, second.closes)
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `a failed retirement close keeps its handle for a later release`() = runTest {
        val native = FakeSession(session("new")).apply { closeFailure = IllegalStateException("close failed") }
        val cells = FacadeCellSessions(FakeFacade(ArrayDeque(listOf(native))))
        cells.open(key, route, null)
        assertEquals(ReleaseOutcome.Unconfirmed, cells.release(key, native.ref, ReleaseMode.Retire))
        assertTrue(cells.hasPendingRelease(ORGANISM))
        native.closeFailure = null
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, native.ref, ReleaseMode.Lyse))
        assertEquals(2, native.closes)
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }
}
