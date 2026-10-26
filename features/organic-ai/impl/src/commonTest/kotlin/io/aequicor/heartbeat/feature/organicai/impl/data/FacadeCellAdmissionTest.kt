package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FacadeCellAdmissionTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, null, null)

    @Test
    fun `a wrapper acquired before lysis cannot submit afterwards`() = runTest {
        val native = FakeSession(session("new"))
        val cells = cellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val handle = cells.open(key, route, null)
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, native.ref, ReleaseMode.Lyse))
        assertFailsWith<EngineException> { handle.submit(RequestId("late"), "work", null) }
        assertTrue(native.prompts.isEmpty())
    }

    @Test
    fun `an admitted send still showing Ready keeps lysis unconfirmed through caller cancellation`() = runTest {
        val proceed = CompletableDeferred<Unit>()
        val native = FakeSession(session("new")).apply {
            finish = null
            beforeSend = { proceed.await() }
        }
        val cells = cellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val handle = cells.open(key, route, null)
        val sending = launch { handle.submit(RequestId("pending"), "work", null) }
        runCurrent()
        assertEquals(ActiveSessionState.Ready(), native.state.value)
        assertEquals(ReleaseOutcome.Unconfirmed, cells.release(key, native.ref, ReleaseMode.Lyse))
        sending.cancel()
        runCurrent()
        assertEquals(ReleaseOutcome.Unconfirmed, cells.release(key, native.ref, ReleaseMode.Lyse))
        assertEquals(0, native.closes)
        assertFalse(native.isArchived)
        proceed.complete(Unit)
        sending.join()
        assertEquals(1, native.prompts.size)
        assertEquals(ReleaseOutcome.Stopped, cells.release(key, native.ref, ReleaseMode.Lyse))
        assertEquals(1, native.cancelled.size)
        assertEquals(1, native.closes)
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `sleep revokes old wrappers and recovery cannot duplicate a still admitted send`() = runTest {
        val proceed = CompletableDeferred<Unit>()
        val native = FakeSession(session("new")).apply {
            finish = null
            beforeSend = { proceed.await() }
        }
        val cells = cellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val old = cells.open(key, route, null, generation = 1)
        val sending = launch { old.submit(RequestId("pending"), "work", null) }
        runCurrent()
        cells.releaseAll()
        assertEquals(0, native.closes)
        // Keep the generation equal: this specifically verifies the sleep epoch fence.
        val recovering = async { cells.open(key, route, native.ref, generation = 1) }
        runCurrent()
        assertFalse(recovering.isCompleted)
        proceed.complete(Unit)
        sending.join()
        val recovered = recovering.await()
        assertNotNull(recovered.activeTurn())
        assertFailsWith<EngineException> { old.submit(RequestId("stale"), "work", null) }
        assertEquals(1, native.prompts.size)
        cells.releaseAll()
        assertEquals(1, native.closes)
    }

    @Test
    fun `a newer generation revokes a wrapper without requiring sleep`() = runTest {
        val native = FakeSession(session("new"))
        val cells = cellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val old = cells.open(key, route, null, generation = 1)
        val current = cells.open(key, route, native.ref, generation = 2)
        assertFailsWith<EngineException> { old.submit(RequestId("stale"), "work", null) }
        current.submit(RequestId("current"), "work", null)
        assertEquals(listOf(RequestId("current")), native.prompts.map { it.id })
        cells.releaseAll()
    }
}
