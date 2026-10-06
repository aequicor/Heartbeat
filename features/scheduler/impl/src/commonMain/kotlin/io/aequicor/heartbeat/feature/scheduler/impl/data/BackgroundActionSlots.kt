package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared admission for standalone actions and graphs: eight per profile, three per owning session. */
@SingleIn(ProfileScope::class)
@Inject
internal class BackgroundActionSlots {
    private val log = Log.tag("BackgroundActionSlots")
    private val lock = Mutex()
    private val occupied = MutableStateFlow<Map<ActionId, SessionRef>>(emptyMap())
    private var isRestoring = false
    val state: StateFlow<Map<ActionId, SessionRef>> = occupied.asStateFlow()

    suspend fun reserve(id: ActionId, owner: SessionRef): String? = lock.withLock {
        when {
            isRestoring -> "the scheduler is restoring running actions"

            id in occupied.value -> "this action already runs"

            occupied.value.size >= PROFILE_LIMIT -> "the profile already runs 8 background actions"

            occupied.value.values.count {
                it == owner
            } >= OWNER_LIMIT -> "this session already runs 3 background actions"

            else -> {
                log.v { "background action slot reserved" }
                occupied.value += id to owner
                null
            }
        }
    }

    suspend fun release(id: ActionId) = lock.withLock {
        log.v { "background action slot released" }
        occupied.value -= id
    }

    fun beginRestore() {
        isRestoring = true
    }
    suspend fun finishRestore() = lock.withLock { isRestoring = false }
    suspend fun restore(id: ActionId, owner: SessionRef) = lock.withLock {
        log.v { "background action slot restored" }
        occupied.value += id to owner
    }
    suspend fun transfer(previous: ActionId, next: ActionId, owner: SessionRef): Boolean = lock.withLock {
        if (occupied.value[previous] != owner) return@withLock false
        log.v { "background action slot transferred to recovery" }
        occupied.value = occupied.value - previous + (next to owner)
        true
    }
    private companion object {
        const val PROFILE_LIMIT = 8
        const val OWNER_LIMIT = 3
    }
}
