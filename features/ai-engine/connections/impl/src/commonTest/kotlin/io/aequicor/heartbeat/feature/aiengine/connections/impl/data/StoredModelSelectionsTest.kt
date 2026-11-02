package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.SelectedModelGeneration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoredModelSelectionsTest {
    private val a = EngineTarget(EngineId("engine"), EngineBindingId("binding-a"), ModelId("a"))
    private val b = a.copy(model = ModelId("b"))

    @Test
    fun `legacy read does not write and the next save persists generations through restart`() = runTest {
        val disk = MemorySelectionStore()
        val old = ModelSelection().withDefault(a)
        disk.values.value = mapOf(ModelSelectionKey.name to Json.encodeToString(ModelSelection.serializer(), old))
        assertFalse(disk.values.value.getValue(ModelSelectionKey.name).contains("generations"))
        val repository = repository(disk)
        val read = repository.observe().first()
        assertEquals(old, read)
        assertNull(read.checkGeneration(a))
        assertEquals(0, disk.writes)
        val migrated = repository.update { it }
        assertTrue(migrated.isEnabled(a))
        assertEquals(a, migrated.defaultTarget)
        val generation = assertNotNull(migrated.checkGeneration(a))
        assertEquals(generation, repository(disk).observe().first().checkGeneration(a))
        assertEquals(2, disk.writes, "Repair writes only pending/committed metadata, leaving legacy choices untouched")
        assertFalse(disk.spec.areValuesLogged)
    }

    @Test
    fun `old serializer reads new saves and old writes never lose selected choices`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val saved = repository.update { it.withDefault(a) }
        val legacy = Json.decodeFromString(
            LegacyModelSelection.serializer(),
            disk.values.value.getValue(ModelSelectionKey.name),
        )
        assertEquals(saved.bindings, legacy.bindings)
        assertEquals(a, legacy.defaultTarget)
        val oldEdited = LegacyModelSelection(listOf(BindingModels(b.binding, setOf(b.model))), b)
        disk.values.value += ModelSelectionKey.name to Json.encodeToString(LegacyModelSelection.serializer(), oldEdited)
        val reopened = repository(disk).observe().first()
        assertEquals(setOf(b.model), reopened.enabled(b.binding))
        assertEquals(b, reopened.defaultTarget)
        assertNull(reopened.checkGeneration(b), "A legacy writer cannot confirm new check metadata")
        assertNotNull(repository(disk).update { it }.checkGeneration(b))
    }

    @Test
    fun `changing another model or only the default preserves the existing generation`() = runTest {
        val repository = repository(MemorySelectionStore())
        val first = repository.update { it.withModel(a, true) }
        val generationA = assertNotNull(first.checkGeneration(a))
        val enabledB = repository.update { it.withModel(b, true) }
        assertEquals(generationA, enabledB.checkGeneration(a))
        val generationB = assertNotNull(enabledB.checkGeneration(b))
        assertNotEquals(generationA, generationB)
        val defaultB = repository.update { it.withDefault(b) }
        assertEquals(generationA, defaultB.checkGeneration(a))
        assertEquals(generationB, defaultB.checkGeneration(b))
        val same = repository.update { it }
        assertEquals(defaultB, same)
    }

    @Test
    fun `disable and reenable cannot restore a stale caller generation`() = runTest {
        val repository = repository(MemorySelectionStore())
        val old = repository.update { it.withModel(a, true) }
        val original = assertNotNull(old.checkGeneration(a))
        val disabled = repository.update { it.without(a.binding) }
        assertNull(disabled.checkGeneration(a))
        assertTrue(disabled.generations.isEmpty())
        val restored = repository.update { old }
        assertNotEquals(original, assertNotNull(restored.checkGeneration(a)))
        val forged = repository.update {
            it.copy(generations = listOf(SelectedModelGeneration(a.binding, a.model, original)))
        }
        assertEquals(restored.checkGeneration(a), forged.checkGeneration(a))
    }

    @Test
    fun `malformed or ambiguous generation values remain unknown and are repaired only on save`() = runTest {
        listOf(
            listOf(SelectedModelGeneration(a.binding, a.model, "not-a-generation")),
            List(2) { SelectedModelGeneration(a.binding, a.model, "22222222-2222-2222-2222-222222222222") },
        ).forEach { metadata ->
            val disk = MemorySelectionStore()
            val choice = ModelSelection().withModel(a, true).copy(generations = metadata)
            val projection = choice.copy(generations = emptyList())
            disk.values.value = mapOf(
                ModelSelectionKey.name to Json.encodeToString(ModelSelection.serializer(), choice),
                ModelSelectionGenerationsKey.name to Json.encodeToString(
                    ModelSelectionGenerations.serializer(),
                    ModelSelectionGenerations(projection, projection, metadata, metadata, isCommitted = true),
                ),
            )
            val repository = repository(disk)
            val read = repository.observe().first()
            assertTrue(read.isEnabled(a))
            assertNull(read.checkGeneration(a))
            assertEquals(0, disk.writes)
            assertNotNull(repository.update { it }.checkGeneration(a))
        }
    }

    @Test
    fun `failed metadata finalization preserves saved choices and only proven unchanged generations`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val before = repository.update { it.withDefault(a) }
        disk.isCommitRejected = true
        val saved = repository.update { it.withModel(b, true) }
        assertTrue(saved.isEnabled(b), "A metadata failure must not falsely report that model choices were not saved")
        assertEquals(before.checkGeneration(a), saved.checkGeneration(a))
        assertNull(saved.checkGeneration(b))
        assertEquals(saved, repository(disk).observe().first())
        disk.isCommitRejected = false
        assertNotNull(repository.update { it }.checkGeneration(b))
    }

    @Test
    fun `an active observer never mixes snapshots or invalidates another model during save`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val before = repository.update { it.withDefault(a) }
        val observed = mutableListOf<ModelSelection>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.observe().toList(observed) }
        runCurrent()
        val committing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        disk.beforeCommit = {
            committing.complete(Unit)
            release.await()
        }
        val saving = async { repository.update { it.withModel(b, true) } }
        committing.await()
        runCurrent()
        assertEquals(before, observed.last())
        release.complete(Unit)
        val saved = saving.await()
        runCurrent()
        assertEquals(saved, observed.last())
        assertTrue(observed.all { it.checkGeneration(a) == before.checkGeneration(a) })
        assertNotNull(saved.checkGeneration(b))
    }

    @Test
    fun `metadata finalization cancellation propagates after the choice was saved`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val before = repository.update { it.withDefault(a) }
        disk.isCommitCancelled = true
        assertFailsWith<CancellationException> { repository.update { it.withModel(b, true) } }
        val actual = repository(disk).observe().first()
        assertTrue(actual.isEnabled(b))
        assertEquals(before.checkGeneration(a), actual.checkGeneration(a))
        assertNull(actual.checkGeneration(b))
    }

    @Test
    fun `applied choice followed by cancellation propagates and leaves added generations unknown`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val before = repository.update { it.withDefault(a) }
        disk.isChoiceCancelledAfterApply = true
        assertFailsWith<CancellationException> { repository.update { it.withModel(b, true) } }
        val actual = repository(disk).observe().first()
        assertTrue(actual.isEnabled(b))
        assertEquals(before.checkGeneration(a), actual.checkGeneration(a))
        assertNull(actual.checkGeneration(b))
    }

    @Test
    fun `rejected choice write keeps old choices and their generations without publishing additions`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val before = repository.update { it.withDefault(a) }
        disk.isChoiceRejected = true
        assertFailsWith<IllegalStateException> { repository.update { it.withModel(b, true) } }
        assertEquals(before, repository(disk).observe().first())
    }

    @Test
    fun `missing metadata never removes legacy choices or makes up a successful check`() = runTest {
        val disk = MemorySelectionStore()
        val saved = repository(disk).update { it.withDefault(a) }
        disk.values.value -= ModelSelectionGenerationsKey.name
        val current = repository(disk).observe().first()
        assertEquals(saved.bindings, current.bindings)
        assertEquals(saved.defaultTarget, current.defaultTarget)
        assertNull(current.checkGeneration(a))
    }

    @Test
    fun `concurrent updates read the latest durable selection inside one mutex`() = runTest {
        val disk = MemorySelectionStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        disk.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        val repository = repository(disk)
        val first = async { repository.update { it.withModel(a, true) } }
        entered.await()
        val otherBinding = b.copy(binding = EngineBindingId("binding-b"))
        val second = async { repository.update { it.withModel(otherBinding, true) } }
        runCurrent()
        assertEquals(1, disk.reads, "A queued update must not take a stale before snapshot")
        release.complete(Unit)
        val resultA = first.await()
        val resultB = second.await()
        assertTrue(resultB.isEnabled(a))
        assertTrue(resultB.isEnabled(otherBinding))
        assertEquals(resultA.checkGeneration(a), resultB.checkGeneration(a))
        assertNotNull(resultB.checkGeneration(otherBinding))
    }

    @Test
    fun `failed write never publishes a tentative generation or loses the existing model`() = runTest {
        val disk = MemorySelectionStore()
        val repository = repository(disk)
        val before = repository.update { it.withDefault(a) }
        disk.isWriteRejected = true
        assertFailsWith<IllegalStateException> { repository.update { it.withModel(b, true) } }
        assertEquals(before, repository.observe().first())
        disk.isWriteRejected = false
        val saved = repository.update { it.withModel(b, true) }
        assertEquals(before.checkGeneration(a), saved.checkGeneration(a))
        assertNotNull(saved.checkGeneration(b))
    }

    private fun repository(disk: MemorySelectionStore): StoredModelSelections = StoredModelSelections(
        object : DataStores {
            override val owner = StorageOwner.App
            override fun filesDirectory(name: String): String = error("Not used")
            override fun keyValue(spec: KeyValueSpec): KeyValueStore {
                assertEquals(ModelSelectionSpec, spec)
                return disk
            }
            override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Not used")
            override suspend fun fire(event: DataEvent) = Unit
        },
    )
}

private class MemorySelectionStore : KeyValueStore {
    override val spec = ModelSelectionSpec
    val values = MutableStateFlow<Map<String, String>>(emptyMap())
    var writes = 0
    var reads = 0
    var beforeRead: suspend () -> Unit = {}
    var isWriteRejected = false
    var isChoiceRejected = false
    var isCommitRejected = false
    var isCommitCancelled = false
    var isChoiceCancelledAfterApply = false
    var beforeCommit: suspend () -> Unit = {}

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { decode(key, it[key.name]) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? {
        reads++
        beforeRead()
        return decode(key, values.value[key.name])
    }
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        if (value is ModelSelectionGenerations && value.isCommitted) beforeCommit()
        check(!isWriteRejected) { "Fixture rejects writes" }
        check(!(isChoiceRejected && key.name == ModelSelectionKey.name)) { "Fixture rejects model choice" }
        check(!(isCommitRejected && value is ModelSelectionGenerations && value.isCommitted)) {
            "Fixture rejects metadata commit"
        }
        if (isCommitCancelled && value is ModelSelectionGenerations && value.isCommitted) {
            throw CancellationException("Fixture cancels metadata commit")
        }
        val type = key.type
        check(type is StoreValueType.Json)
        values.value += key.name to Json.encodeToString(type.serializer, value)
        writes++
        if (isChoiceCancelledAfterApply && key.name == ModelSelectionKey.name) {
            throw CancellationException("Fixture cancels after applying choice")
        }
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }
    private fun <T : Any> decode(key: StoreKey<T>, value: String?): T? {
        val type = key.type
        check(type is StoreValueType.Json)
        return value?.let { Json.decodeFromString(type.serializer, it) }
    }
}

/** Exact pre-generations wire shape; Json remains strict so unknown properties would fail this rollback test. */
@Serializable
private data class LegacyModelSelection(
    val bindings: List<BindingModels> = emptyList(),
    val defaultTarget: EngineTarget? = null,
)
