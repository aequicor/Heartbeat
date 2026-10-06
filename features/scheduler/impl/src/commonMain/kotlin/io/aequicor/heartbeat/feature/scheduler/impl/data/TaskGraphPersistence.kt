package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraph
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphEffect
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphIntent
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphOutput
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.api.validationError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal typealias TaskGraphMachine = Machine<TaskGraphState, TaskGraphIntent, TaskGraphOutput>

/** One atomic profile snapshot; a failed decode never silently discards approved or running work. */
@SingleIn(ProfileScope::class)
@Inject
internal class TaskGraphPersistence(
    @ForScope(ProfileScope::class) stores: DataStores,
) : EffectHandler<TaskGraphEffect, TaskGraphIntent> {
    private val log = Log.tag("TaskGraphPersistence")
    private val store = stores.keyValue(KeyValueSpec("scheduler_task_graphs"))
    private val lock = Mutex()
    private var saved = -1L

    override suspend fun handle(effect: TaskGraphEffect, machine: EffectScope<TaskGraphIntent>) {
        when (effect) {
            TaskGraphEffect.Load -> {
                val graphs = lock.withLock {
                    val raw = store.get(SNAPSHOT)
                    val graphs = if (raw == null) emptyList() else decode(raw)
                    saved = -1
                    graphs
                }
                machine.send(TaskGraphIntent.Internal.Loaded(graphs))
            }

            is TaskGraphEffect.Save -> {
                val revision = lock.withLock {
                    if (effect.revision > saved) {
                        store.set(SNAPSHOT, Json.encodeToString(effect.graphs))
                        saved = effect.revision
                        log.v { "stored graph revision=$saved" }
                    }
                    saved
                }
                machine.send(TaskGraphIntent.Internal.Saved(revision))
            }
        }
    }

    private fun decode(raw: String): List<TaskGraph> = try {
        Json.decodeFromString<List<TaskGraph>>(raw).also { graphs ->
            require(graphs.map { it.id }.distinct().size == graphs.size)
            require(
                graphs.all {
                    it.definition.validationError() == null && it.approval.isNotBlank() &&
                        it.runs.keys == it.definition.tasks.map { task -> task.id }.toSet()
                },
            )
        }
    } catch (e: IllegalArgumentException) {
        throw GraphSnapshotException(e::class.simpleName.orEmpty())
    }

    private companion object {
        val SNAPSHOT = stringKey("snapshot_v1")
    }
}

/** Parser diagnostics can contain commands or outputs, so the original exception is intentionally not retained. */
private class GraphSnapshotException(type: String) : IllegalStateException("Unreadable task graph snapshot ($type)")
