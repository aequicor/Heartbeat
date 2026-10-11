package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.Job

/**
 * Main-confined profile ownership of cancelled hosted work surviving a failed or replaced engine runtime. Only the
 * still-pending parent jobs are retained, never their session: a closed session and its runtime stay collectable,
 * and an entry holds nothing beyond completed job handles once its work drains.
 */
@Inject
@SingleIn(ProfileScope::class)
internal class CodexHostedDrains {
    private val retained = mutableMapOf<SessionRef, MutableList<Entry>>()

    /** No retained work remains after the last prune. */
    val isEmpty: Boolean get() = retained.isEmpty()

    fun retain(ref: SessionRef, route: ExecutionRoute, ownership: String?, jobs: CodexHostedJobs) {
        prune()
        val pending = jobs.pending()
        if (pending.isNotEmpty()) retained.getOrPut(ref) { mutableListOf() }.add(Entry(route, ownership, pending))
    }

    /** A native terminal receipt cannot release capacity while an earlier runtime's hosted child still runs. */
    suspend fun drain(ref: SessionRef, route: ExecutionRoute, ownership: String?, turn: TurnId): Boolean {
        val entries = retained[ref]?.toList().orEmpty()
        var isDrained = true
        for (entry in entries) {
            if (entry.route != route || entry.ownership != ownership || !drainHostedParent(entry.parents[turn])) {
                isDrained = false
            }
        }
        prune()
        return isDrained
    }

    private fun prune() {
        retained.values.forEach { entries -> entries.removeAll { it.parents.values.all(Job::isCompleted) } }
        retained.entries.removeAll { it.value.isEmpty() }
    }

    private data class Entry(val route: ExecutionRoute, val ownership: String?, val parents: Map<TurnId, Job>)
}
