package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchedulerPersistenceTest {
    @Test
    fun `revision is acknowledged only after its write completes and never goes backwards`() = runTest {
        val mayWrite = CompletableDeferred<Unit>()
        val storage = object : WakeStorage {
            override suspend fun load(): List<ScheduledWake> = emptyList()

            override suspend fun save(wakes: List<ScheduledWake>) {
                mayWrite.await()
            }
        }
        val persistence = SchedulerPersistence(storage)
        assertEquals(-1L, persistence.revision.value)
        persistence.load()
        val write = async { persistence.persist(SchedulerEffect.Persist(emptyList(), 2)) }
        runCurrent()
        assertEquals(0L, persistence.revision.value)
        mayWrite.complete(Unit)
        write.await()
        assertEquals(2L, persistence.revision.value)
        persistence.persist(SchedulerEffect.Persist(emptyList(), 1))
        assertEquals(2L, persistence.revision.value)
    }

    @Test
    fun `failed writes do not acknowledge a memory-only settlement`() = runTest {
        val storage = object : WakeStorage {
            override suspend fun load(): List<ScheduledWake> = emptyList()

            override suspend fun save(wakes: List<ScheduledWake>) = error("storage unavailable")
        }
        val persistence = SchedulerPersistence(storage)
        persistence.load()
        assertFailsWith<IllegalStateException> { persistence.persist(SchedulerEffect.Persist(emptyList(), 1)) }
        assertEquals(0L, persistence.revision.value)
    }
}
