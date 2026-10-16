package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.runs

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.MAX_SUBAGENT_RUNS
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentResult
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRun
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KeyValueSubagentRunsTest {
    @Test
    fun `concurrent reservations for the last slot never evict an unread run`() = runTest {
        val stores = MemoryStores()
        val runs = KeyValueSubagentRuns(stores)
        repeat(MAX_SUBAGENT_RUNS - 1) { assertTrue(runs.reserve(run(it))) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        stores.store.beforeWrite = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { runs.reserve(run(47)) }
        entered.await()
        val second = async { runs.reserve(run(48)) }
        runCurrent()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals((0 until MAX_SUBAGENT_RUNS).map(::run), runs.list())
        assertEquals(runs.list(), KeyValueSubagentRuns(stores).list())
        runs.remove(setOf("0"))
        assertTrue(runs.reserve(run(48)))
        assertEquals(MAX_SUBAGENT_RUNS, runs.list().size)
    }

    @Test
    fun `delivery snapshots survive reopening and preserve unrelated runs`() = runTest {
        val stores = MemoryStores()
        val runs = KeyValueSubagentRuns(stores)
        assertTrue(runs.reserve(run(0)))
        assertTrue(runs.reserve(run(1)))
        val result = SubagentResult(true, "Complete answer\nwith details")
        runs.saveResults(mapOf("0" to result))
        val restored = KeyValueSubagentRuns(stores).list()
        assertEquals(listOf(run(0).copy(result = result), run(1)), restored)
        assertEquals(restored, Json.decodeFromString<List<SubagentRun>>(Json.encodeToString(restored)))
    }

    @Test
    fun `legacy inbox records without delivery snapshots remain readable`() {
        val legacy = """
            {"id":"0","parent":{"engine":{"value":"engine"},"source":{"value":"local"},"nativeId":"parent"},
             "title":"Title","model":"Model","startedAt":"2026-10-16T00:00:00Z"}
        """.trimIndent()
        val restored = Json.decodeFromString<SubagentRun>(legacy)
        assertNull(restored.result)
        assertEquals(run(0).copy(startedAt = Instant.parse("2026-10-16T00:00:00Z")), restored)
    }

    private fun run(id: Int) = SubagentRun(
        id.toString(),
        SessionRef(EngineId("engine"), SessionSourceId("local"), "parent"),
        "Title",
        "Model",
        startedAt = Instant.DISTANT_PAST,
    )

    private class MemoryStores : DataStores {
        val store = MemoryStore()
        override val owner: StorageOwner = StorageOwner.App
        override fun filesDirectory(name: String): String = error("unused")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    private class MemoryStore : KeyValueStore {
        override lateinit var spec: KeyValueSpec
        private val values = mutableMapOf<String, Any>()
        var beforeWrite: suspend () -> Unit = {}
        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

        @Suppress("UNCHECKED_CAST") // Test fake keeps one value type per key.
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = values[key.name] as T?

        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            beforeWrite()
            values[key.name] = value
        }

        override suspend fun remove(key: StoreKey<*>) {
            values.remove(key.name)
        }

        override suspend fun clear() = values.clear()
    }
}
