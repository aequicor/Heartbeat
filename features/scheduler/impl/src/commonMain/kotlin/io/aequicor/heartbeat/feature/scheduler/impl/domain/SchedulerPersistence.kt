package io.aequicor.heartbeat.feature.scheduler.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises wake writes and acknowledges only revisions that reached storage, so action results retire safely. */
internal class SchedulerPersistence(private val storage: WakeStorage) {
    private val log = Log.tag("SchedulerPersistence")
    private val writes = Mutex()
    private val stored = MutableStateFlow(-1L)

    /** Last durable revision of this machine run; zero is its successfully loaded initial snapshot. */
    val revision: StateFlow<Long> = stored.asStateFlow()

    suspend fun load(): List<ScheduledWake> = writes.withLock {
        storage.load().also {
            stored.value = 0
            log.v { "loaded durable wake revision=0" }
        }
    }

    suspend fun persist(effect: SchedulerEffect.Persist) = writes.withLock {
        if (effect.revision <= stored.value) {
            log.v { "skip stale write revision=${effect.revision}" }
            return@withLock
        }
        storage.save(effect.wakes)
        stored.value = effect.revision
        log.v { "stored durable wake revision=${effect.revision}" }
    }
}
