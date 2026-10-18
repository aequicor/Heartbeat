package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All live profile journals participate before any session of a repository can resume. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class VirtualSourceBarriers : VirtualSourceBarrier {
    private val registry = Mutex()
    private val projects = mutableMapOf<String, Barrier>()

    override suspend fun refresh(
        project: String,
        owner: Any,
        participant: VirtualSourceParticipant,
        explicit: Boolean,
        resolve: Boolean,
    ) {
        val barrier = registry.withLock { projects.getOrPut(project) { Barrier() } }
        barrier.lock.withLock {
            barrier.participants[owner] = participant
            val participants = barrier.participants.values.toList()
            var paused = false
            participants.forEach { if (it.inspect()) paused = true }
            if (!paused && !explicit) return@withLock
            participants.forEach { it.pause(paused) }
            var stopped = true
            participants.forEach { if (!it.stop()) stopped = false }
            if (stopped) participants.forEach { it.resume(explicit, resolve) }
        }
    }

    override suspend fun detach(owner: Any) {
        val barriers = registry.withLock { projects.values.toList() }
        barriers.forEach { barrier -> barrier.lock.withLock { barrier.participants.remove(owner) } }
    }

    private class Barrier {
        val lock = Mutex()
        val participants = mutableMapOf<Any, VirtualSourceParticipant>()
    }
}

internal interface VirtualSourceParticipant {
    suspend fun inspect(): Boolean
    suspend fun pause(all: Boolean)
    suspend fun stop(): Boolean
    suspend fun resume(explicit: Boolean, resolve: Boolean)
}

internal interface VirtualSourceBarrier {
    suspend fun refresh(
        project: String,
        owner: Any,
        participant: VirtualSourceParticipant,
        explicit: Boolean,
        resolve: Boolean,
    )
    suspend fun detach(owner: Any)
}
