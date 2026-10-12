package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationTools
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ContextOffloadTest {
    private val outputs = KeyValueContextOutputs(MemoryStores(), Clock.System)
    private val view = object : ContextOptimizationView {
        override val state = MutableStateFlow<ContextOptimizationState>(
            ContextOptimizationState.Ready(ContextOptimizationSettings(offloadThresholdChars = 4_000)),
        )
    }
    private val offload = ContextOutputOffload(view, EnabledToggles(), outputs)
    private val recall = ContextRecallTools(EnabledToggles(), outputs)

    @Test
    fun `long output is stored and shortened, short output stays`() = runTest {
        val output = "a".repeat(3_000) + "b".repeat(3_000) + "c".repeat(3_000)

        val stub = assertNotNull(offload.offload(Session, "run_command", output))

        assertTrue(stub.startsWith("a".repeat(2_000)) && stub.contains("c".repeat(1_000)) && "context_recall" in stub)
        assertTrue(stub.length < output.length)
        assertNull(offload.offload(Session, "run_command", "short"))
        assertNull(offload.offload(Session, ContextOptimizationTools.RECALL, output))
    }

    @Test
    fun `recall reads parts of an output of the same session only`() = runTest {
        val output = "x".repeat(5_000) + "TAIL"
        val stub = assertNotNull(offload.offload(Session, "read_file", output))
        val id = Regex("id=\"(\\w+)\"").find(stub)!!.groupValues[1]

        val part = recall.execute(context(Session), ContextOptimizationTools.RECALL, arguments(id, 5_000))
        val foreign = recall.execute(context(Other), ContextOptimizationTools.RECALL, arguments(id, 0))

        assertTrue(part.text.startsWith("TAIL") && "This is the end" in part.text)
        assertTrue(foreign.isError)
    }

    @Test
    fun `the oldest outputs are dropped beyond the limit`() = runTest {
        val first = outputs.put(StoredOutput(Session, "tool", "first")).id
        repeat(64) { outputs.put(StoredOutput(Session, "tool", "next $it")) }

        assertNull(outputs.get(first))
    }

    private fun context(session: SessionRef) = AgentToolContext(session = session, workspace = null, turn = TurnId("t"))

    private fun arguments(id: String, offset: Int) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "offset" to JsonPrimitive(offset)))

    private class EnabledToggles : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(enabled<T>())
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled()

        @Suppress("UNCHECKED_CAST") // The offload reads only flags.
        private fun <T : Any> enabled(): T = true as T
    }

    private class MemoryStores : DataStores {
        private val stores = mutableMapOf<KeyValueSpec, MemoryStore>()
        override val owner = StorageOwner.App
        override fun filesDirectory(name: String): String = error("unused")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(spec) { MemoryStore(spec) }
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    private class MemoryStore(override val spec: KeyValueSpec) : KeyValueStore {
        private val values = MutableStateFlow(emptyMap<String, String>())
        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { decode(key, it[key.name]) }
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = decode(key, values.value[key.name])
        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            val json = key.type as StoreValueType.Json<T>
            values.value += key.name to Json.encodeToString(json.serializer, value)
        }

        override suspend fun remove(key: StoreKey<*>) {
            values.value -= key.name
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }

        @Suppress("UNCHECKED_CAST") // Every key of the offload store is JSON.
        private fun <T : Any> decode(key: StoreKey<T>, value: String?): T? =
            value?.let { Json.decodeFromString((key.type as StoreValueType.Json<T>).serializer, it) }
    }

    private companion object {
        val Session = SessionRef(EngineId("koog"), SessionSourceId("s"), "one")
        val Other = SessionRef(EngineId("koog"), SessionSourceId("s"), "two")
    }
}
