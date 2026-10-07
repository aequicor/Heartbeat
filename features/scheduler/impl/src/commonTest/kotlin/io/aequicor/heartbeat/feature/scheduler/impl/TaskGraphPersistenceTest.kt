package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphEffect
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphIntent
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphPersistence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskGraphPersistenceTest {
    @Test
    fun `atomic snapshot survives a new persistence instance and older writes cannot replace it`() = runTest {
        val stores = MemoryStores()
        val sent = mutableListOf<TaskGraphIntent>()
        val effects = object : EffectScope<TaskGraphIntent> {
            override suspend fun send(intent: TaskGraphIntent): SendResult {
                sent += intent
                return SendResult.Accepted
            }
        }
        val f = GraphFixture(this)
        val graphs = listOf(f.graph(listOf(f.command("A"))))
        val writer = TaskGraphPersistence(stores)
        writer.handle(TaskGraphEffect.Save(graphs, 2), effects)
        writer.handle(TaskGraphEffect.Save(emptyList(), 1), effects)
        assertEquals<List<TaskGraphIntent>>(
            listOf(TaskGraphIntent.Internal.Saved(2), TaskGraphIntent.Internal.Saved(2)),
            sent,
        )
        TaskGraphPersistence(stores).handle(TaskGraphEffect.Load, effects)
        assertEquals(graphs, assertIs<TaskGraphIntent.Internal.Loaded>(sent.last()).graphs)
    }

    @Test
    fun `corrupt persisted graph fails closed and does not become an empty schedule`() = runTest {
        val stores = MemoryStores()
        stores.store.values["snapshot_v1"] = "corrupt"
        var wasLoaded = false
        val effects = object : EffectScope<TaskGraphIntent> {
            override suspend fun send(intent: TaskGraphIntent): SendResult {
                wasLoaded = true
                return SendResult.Accepted
            }
        }
        assertFailsWith<IllegalStateException> { TaskGraphPersistence(stores).handle(TaskGraphEffect.Load, effects) }
        assertTrue(!wasLoaded)
    }
}
