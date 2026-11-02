package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CellPublicationTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, null, null)

    @Test
    fun `a failed ref publication remains owned and is retried before release`() = runTest {
        val native = FakeSession(session("unpublished"))
        val facade = FakeFacade(ArrayDeque(listOf(native)))
        val cells = cellSessions(facade)
        var writes = 0
        val saved = mutableSetOf<SessionRef>()
        assertFailsWith<IllegalStateException> {
            cells.open(key, route, null, onOpened = {
                writes++
                if (writes == 1) error("write failed")
                saved += it
            })
        }
        assertTrue(cells.hasPendingRelease(ORGANISM))
        assertEquals(0, native.closes)
        assertEquals(setOf(native.ref), cells.prepareRelease(key, 0, isFinal = true))
        assertEquals(setOf(native.ref), saved)
        assertEquals(1, facade.creations.size)
        assertEquals(ReleaseOutcome.Stopped, cells.closeReleased(key, 0))
        assertFalse(cells.hasPendingRelease(ORGANISM))
    }

    @Test
    fun `late create publishes its ref before a sleep rejects its handle`() = runTest {
        val native = FakeSession(session("late"))
        val facade = FakeFacade(ArrayDeque(listOf(native)))
        val cells = cellSessions(facade)
        val gate = CompletableDeferred<Unit>()
        facade.onCreate = { gate.await() }
        var saved: SessionRef? = null
        val opening = async {
            assertFailsWith<EngineException> {
                cells.open(key, route, null, onOpened = {
                    assertEquals(0, native.closes)
                    saved = it
                })
            }
        }
        runCurrent()
        cells.freezeAdmissions()
        gate.complete(Unit)
        opening.await()
        assertEquals(native.ref, saved)
        assertTrue(native.prompts.isEmpty())
        assertEquals(1, facade.creations.size)
    }
}
