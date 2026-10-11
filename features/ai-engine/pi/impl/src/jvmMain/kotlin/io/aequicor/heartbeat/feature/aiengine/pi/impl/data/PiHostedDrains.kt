package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId

/** Main-confined profile ownership of cancelled hosted work surviving a failed or replaced engine runtime. */
@Inject
@SingleIn(ProfileScope::class)
internal class PiHostedDrains {
    private val retained = mutableMapOf<SessionRef, MutableSet<Entry>>()

    fun retain(ref: SessionRef, route: ExecutionRoute, ownership: String?, jobs: PiHostedJobs) {
        prune()
        if (jobs.hasPending) retained.getOrPut(ref) { mutableSetOf() }.add(Entry(route, ownership, jobs))
    }

    /** A native terminal receipt cannot release capacity while an earlier runtime's hosted child still runs. */
    suspend fun drain(ref: SessionRef, route: ExecutionRoute, ownership: String?, turn: TurnId): Boolean {
        val entries = retained[ref]?.toList().orEmpty()
        var isDrained = true
        for (entry in entries) {
            if (entry.route != route || entry.ownership != ownership || !entry.jobs.drain(turn)) isDrained = false
        }
        prune()
        return isDrained
    }

    private fun prune() {
        retained.values.forEach { entries -> entries.removeAll { !it.jobs.hasPending } }
        retained.entries.removeAll { it.value.isEmpty() }
    }

    private data class Entry(val route: ExecutionRoute, val ownership: String?, val jobs: PiHostedJobs)
}
