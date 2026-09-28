package io.aequicor.heartbeat.feature.aiengine.koog.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KoogStorageTest {
    private val stores = FakeDataStores()
    private val connections = StoredKoogConnections(stores)
    private val records = StoredKoogSessionRecords(stores)

    @Test
    fun `connections are saved replaced and removed by binding`() = runTest {
        connections.put(connection("a", local()))
        connections.put(connection("b", local()))
        connections.put(connection("a", local()))
        assertEquals(listOf("b", "a"), connections.list().map { it.binding.id.value })
        connections.remove(EngineBindingId("b"))
        assertEquals(listOf("a"), connections.list().map { it.binding.id.value })
    }

    @Test
    fun `changed source metadata requires a new revision and reaches every binding`() = runTest {
        connections.put(connection("a", local()))
        connections.put(connection("b", local()))
        assertFailsWith<IllegalArgumentException> { connections.put(connection("a", local(label = "Renamed"))) }
        connections.put(connection("a", local(label = "Renamed", revision = "2")))
        assertEquals(listOf("Renamed", "Renamed"), connections.list().map { it.source.info.label })
    }

    @Test
    fun `unsupported source is rejected`() = runTest {
        val cloudWithoutKey = AuthSource.NoAuth(
            AuthSourceInfo(AuthSourceId("local"), "Cloud", AuthRevision.Known("1")),
            AuthScope(KoogProvider.OpenAI.id, KoogProvider.OpenAI.origin),
        )
        assertFailsWith<IllegalArgumentException> { connections.put(connection("a", cloudWithoutKey)) }
        assertEquals(emptyList(), connections.list())
    }

    @Test
    fun `unreadable connection is hidden but survives writes of the others`() = runTest {
        connections.put(connection("a", local()))
        val store = stores.keyValue(KeyValueSpec("ai_koog_connections")) as FakeKeyValueStore
        val stored = store.raw("connections")
        val foreign = """{"binding":{"id":"future"},"source":{"type":"future.kind"}}"""
        store.values.value = mapOf("connections" to stored.dropLast(1) + ",$foreign]")

        assertEquals(listOf("a"), connections.list().map { it.binding.id.value })
        connections.put(connection("b", local()))
        assertEquals(listOf("a", "b"), connections.list().map { it.binding.id.value })
        assertContains(store.raw("connections"), "future.kind")
    }

    @Test
    fun `fields unknown to this version survive writes of other elements`() = runTest {
        records.save(record("one"))
        val store = stores.keyValue(KeyValueSpec("ai_koog_sessions")) as FakeKeyValueStore
        store.values.value = mapOf("sessions" to store.raw("sessions").replaceFirst("{", """{"future":1,"""))

        assertEquals(listOf("one"), records.list().map { it.summary.ref.nativeId })
        records.save(record("two"))
        assertContains(store.raw("sessions"), """"future":1""")
    }

    @Test
    fun `elements differing only in unknown fields stay separate`() = runTest {
        records.save(record("one"))
        val store = stores.keyValue(KeyValueSpec("ai_koog_sessions")) as FakeKeyValueStore
        val element = store.raw("sessions").removePrefix("[").removeSuffix("]")
        val first = element.replaceFirst("{", """{"future":1,""")
        val second = element.replaceFirst("{", """{"future":2,""")
        store.values.value = mapOf("sessions" to "[$first,$second]")

        records.save(record("two"))
        assertContains(store.raw("sessions"), """"future":1""")
        assertContains(store.raw("sessions"), """"future":2""")
    }

    @Test
    fun `corrupt value is never overwritten`() = runTest {
        val store = stores.keyValue(KeyValueSpec("ai_koog_sessions")) as FakeKeyValueStore
        store.values.value = mapOf("sessions" to "not json")

        assertEquals(emptyList(), records.list())
        assertFailsWith<IllegalStateException> { records.save(record("one")) }
        assertEquals("not json", store.raw("sessions"))
    }

    @Test
    fun `session record is upserted by ref in place of the previous checkpoint`() = runTest {
        records.save(record("one"))
        records.save(record("two"))
        records.save(record("one", title = "Updated"))
        assertEquals(listOf("two", "one"), records.list().map { it.summary.ref.nativeId })
        assertEquals("Updated", records.get(ref("one"))?.summary?.title)
        assertNull(records.get(ref("missing")))
    }

    private fun local(label: String = "Local", revision: String = "1") = AuthSource.NoAuth(
        AuthSourceInfo(AuthSourceId("local"), label, AuthRevision.Known(revision)),
        AuthScope(KoogProvider.Ollama.id, KoogProvider.Ollama.origin),
    )

    private fun connection(binding: String, source: AuthSource) =
        KoogConnection(EngineBinding(EngineBindingId(binding), KoogEngineId, source.info.id), source)

    private fun ref(id: String) = SessionRef(KoogEngineId, SessionSourceId("koog.profile"), id)

    private fun record(id: String, title: String? = null) =
        KoogRecord(SessionSummary(ref(id), title = title), ModelId("model"))
}

/** In-memory profile [DataStores]: key-value only. */
private class FakeDataStores : DataStores {
    private val stores = mutableMapOf<String, FakeKeyValueStore>()
    override val owner: StorageOwner = StorageOwner.App
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(spec.name) { FakeKeyValueStore(spec) }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("No databases in this test")
    override suspend fun fire(event: DataEvent) = Unit
}

/** Stores text values only, which is how the real store keeps JSON as well. */
private class FakeKeyValueStore(override val spec: KeyValueSpec) : KeyValueStore {
    val values = MutableStateFlow(emptyMap<String, String>())

    fun raw(name: String): String = values.value.getValue(name)

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it.read(key) }

    override suspend fun <T : Any> get(key: StoreKey<T>): T? = values.value.read(key)

    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        values.value += key.name to value as String
    }

    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }

    override suspend fun clear() {
        values.value = emptyMap()
    }

    @Suppress("UNCHECKED_CAST") // Test fake: only text keys are supported, checked below.
    private fun <T : Any> Map<String, String>.read(key: StoreKey<T>): T? {
        check(key.type == StoreValueType.Text) { "Unsupported in this test: ${key.type}" }
        return this[key.name] as T?
    }
}
