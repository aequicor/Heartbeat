package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionAccess
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionAdmissions
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionOpening
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoredSessionExecutionBindingsTest {
    private val ref = SessionRef(EngineId("test"), SessionSourceId("source"), "native")
    private val binding = SessionExecutionBinding(SessionExecutionInstance("test", "first"))

    @Test
    fun `opening survives restart and only its exact claim atomically establishes authority`() = runTest {
        val disk = BindingTestStore()
        val opening = SessionExecutionOpening("first-open", ref.engine, binding)
        StoredSessionExecutionBindings(disk).reserve(opening)
        val restarted = StoredSessionExecutionBindings(disk)
        assertEquals(listOf(opening), restarted.pending())
        assertFailsWith<IllegalStateException> {
            restarted.complete(opening.copy(token = "different-open"), ref)
        }
        assertEquals(listOf(opening), restarted.pending())
        assertNull(restarted.find(ref))
        restarted.complete(opening, ref)
        val published = StoredSessionExecutionBindings(disk)
        assertEquals(emptyList(), published.pending())
        assertEquals(binding, published.find(ref))
        published.complete(opening, ref)
        assertEquals(binding, published.find(ref))
    }

    @Test
    fun `binding survives restart and cannot be replaced by another incarnation`() = runTest {
        val disk = BindingTestStore()
        StoredSessionExecutionBindings(disk).bind(ref, binding)
        val restored = StoredSessionExecutionBindings(disk)
        assertEquals(binding, restored.find(ref))
        assertEquals(setOf(ref), restored.references(binding.instance))
        assertFailsWith<IllegalStateException> {
            restored.bind(ref, binding.copy(instance = binding.instance.copy(id = "second")))
        }
        assertEquals(binding, restored.find(ref))
        assertNull(restored.find(ref.copy(nativeId = "unknown")))
    }

    @Test
    fun `omitting host metadata after restart cannot bypass a missing canonical authority`() = runTest {
        val disk = BindingTestStore()
        StoredSessionExecutionBindings(disk).bind(ref, binding)
        val restored = SessionExecutionAccess(
            StoredSessionExecutionBindings(disk),
            SessionExecutionAdmissions(lazyOf(emptySet())),
        )
        assertFailsWith<IllegalStateException> { restored.resolve(ref, null, null) }
    }

    @Test
    fun `future duplicate and corrupt records fail closed without erasing the original storage`() = runTest {
        val disk = BindingTestStore()
        val storage = StoredSessionExecutionBindings(disk)
        storage.bind(ref, binding)
        val valid = disk.raw!!
        val entry = valid.substringAfter("\"entries\":[").substringBeforeLast("]")
        for (bad in listOf("not-json", "{\"version\":2}", "{\"entries\":[$entry,$entry]}")) {
            disk.raw = bad
            assertFailsWith<Exception> { storage.find(ref) }
            assertEquals(bad, disk.raw)
        }
    }
}

private class BindingTestStore : KeyValueStore {
    override val spec: KeyValueSpec = SessionExecutionBindingSpec
    var raw: String? = null
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = flow { emit(get(key)) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? {
        val type = key.type as StoreValueType.Json<T>
        return raw?.let { Json.decodeFromString(type.serializer, it) }
    }
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        check(retention == Retention.Permanent)
        val type = key.type as StoreValueType.Json<T>
        raw = Json.encodeToString(type.serializer, value)
    }
    override suspend fun remove(key: StoreKey<*>) = error("Authority metadata must remain")
    override suspend fun clear() = error("Authority metadata must remain")
}
