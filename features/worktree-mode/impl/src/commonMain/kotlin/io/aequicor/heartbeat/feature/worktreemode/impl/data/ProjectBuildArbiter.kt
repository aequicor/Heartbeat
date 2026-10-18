package io.aequicor.heartbeat.feature.worktreemode.impl.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Capacity leases shared by independent project queues; this class has no waiting queue. */
internal class ProjectBuildArbiter {
    private val log = io.aequicor.heartbeat.core.logging.Log.tag("ProjectBuildArbiter")
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, RunningBuild>()
    val changes = MutableStateFlow(0L)

    suspend fun admit(
        waiting: List<QueuedBuild>,
        running: List<RunningBuild>,
        budget: BuildBudget,
        external: List<RunningBuild> = emptyList(),
    ): BuildAdmission = mutex.withLock {
        running.forEach { if (it.id !in leases) leases[it.id] = it }
        val admission = admitBuilds(waiting, leases.values.toList() + external, budget)
        waiting.filter { it.id in admission.admitted }.forEach {
            leases[it.id] = RunningBuild(
                it.id,
                it.project,
                it.cost,
                it.resources,
                it.slot?.pool,
                admission.admitted[it.id],
            )
        }
        admission
    }

    suspend fun release(id: String) = mutex.withLock {
        if (leases.remove(id) != null) {
            changes.value += 1
            log.v { "Application build resource lease released" }
        }
    }
}
