package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.runs

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.MAX_SUBAGENT_RUNS
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentResult
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRun
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRunStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Durable reservations in the profile store; a full registry refuses admission without losing unread results. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueSubagentRuns(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : SubagentRunStore {
    private val log = Log.tag("SubagentRuns")
    private val store by lazy { stores.keyValue(SPEC) }
    private val lock = Mutex()

    override suspend fun list(): List<SubagentRun> = lock.withLock { store.get(KEY).orEmpty() }

    override suspend fun reserve(run: SubagentRun): Boolean = lock.withLock {
        val current = store.get(KEY).orEmpty()
        if (current.size >= MAX_SUBAGENT_RUNS || current.any { it.id == run.id }) return@withLock false
        write(current + run)
        true
    }

    override suspend fun saveResults(results: Map<String, SubagentResult>) = lock.withLock {
        if (results.isEmpty()) return@withLock
        val current = store.get(KEY).orEmpty()
        check(results.keys.all { id -> current.any { it.id == id } }) { "Subagent result has no reservation" }
        write(current.map { run -> results[run.id]?.let { run.copy(result = it) } ?: run })
    }

    override suspend fun remove(ids: Set<String>) = lock.withLock {
        write(
            store.get(KEY).orEmpty().filterNot { it.id in ids },
        )
    }

    private suspend fun write(runs: List<SubagentRun>) {
        log.v { "write subagent runs: ${runs.size}" }
        if (runs.isEmpty()) store.remove(KEY) else store.set(KEY, runs)
    }

    private companion object {
        val SPEC = KeyValueSpec("adaptive_subagents_runs")
        val KEY = jsonKey("runs", ListSerializer(SubagentRun.serializer()))
    }
}
