package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.impl.data.ScheduleOutcome
import io.aequicor.heartbeat.feature.scheduler.impl.data.WakeScheduler
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WakeSchedulerTest {
    private val request = wakeRequest("durable", deadline = START + 60.seconds)

    private fun scheduler(machine: SpecMachine, persistence: SchedulerPersistence) =
        WakeScheduler(machine, lazyOf(setOf(FakeHost(priority = 1))), persistence)

    @Test
    fun `successful scheduling waits for storage and survives reopening`() = runTest {
        val storage = GatedStorage()
        val persistence = SchedulerPersistence(storage)
        persistence.load()
        val machine = SpecMachine()
        persistEffects(machine, persistence)
        val result = async { scheduler(machine, persistence).schedule(request, START) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0L, persistence.revision.value)
        storage.mayWrite.complete(Unit)
        assertEquals(ScheduleOutcome.Scheduled, result.await())
        assertEquals(listOf(request), SchedulerPersistence(storage).load().map { it.request })
    }

    @Test
    fun `a cancelled profile write never confirms the wake`() = runTest {
        val storage = GatedStorage()
        val persistence = SchedulerPersistence(storage)
        persistence.load()
        val machine = SpecMachine()
        val result = async { scheduler(machine, persistence).schedule(request, START) }
        runCurrent()
        val write = async { persistence.persist(machine.effects.filterIsInstance<SchedulerEffect.Persist>().single()) }
        runCurrent()
        assertFalse(result.isCompleted)
        write.cancelAndJoin()
        assertEquals(ScheduleOutcome.Unconfirmed, result.await())
        assertEquals(emptyList(), SchedulerPersistence(storage).load())
    }

    @Test
    fun `cancelling the caller does not block on the durable write`() = runTest {
        val storage = GatedStorage()
        val persistence = SchedulerPersistence(storage)
        persistence.load()
        val machine = SpecMachine()
        persistEffects(machine, persistence)
        val result = async { scheduler(machine, persistence).schedule(request, START) }
        runCurrent()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        storage.mayWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(request), SchedulerPersistence(storage).load().map { it.request })
    }

    @Test
    fun `a failed write never confirms the wake`() = runTest {
        val storage = object : WakeStorage {
            override suspend fun load(): List<ScheduledWake> = emptyList()
            override suspend fun save(wakes: List<ScheduledWake>) = error("disk unavailable")
        }
        val persistence = SchedulerPersistence(storage)
        persistence.load()
        val machine = SpecMachine()
        val result = async { scheduler(machine, persistence).schedule(request, START) }
        runCurrent()
        assertFailsWith<IllegalStateException> {
            persistence.persist(machine.effects.filterIsInstance<SchedulerEffect.Persist>().single())
        }
        assertEquals(ScheduleOutcome.Unconfirmed, result.await())
        assertEquals(0L, persistence.revision.value)
    }

    private class GatedStorage : WakeStorage {
        val mayWrite = CompletableDeferred<Unit>()
        private var stored = emptyList<ScheduledWake>()

        override suspend fun load(): List<ScheduledWake> = stored

        override suspend fun save(wakes: List<ScheduledWake>) {
            mayWrite.await()
            stored = wakes
        }
    }
}
