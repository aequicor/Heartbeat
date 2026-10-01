package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StoredPromptResourceHistoryTest {
    private val session = SessionRef(EngineId("engine"), SessionSourceId("native"), "private-session")
    private val parts = listOf(
        ContentPart.Text("Explain"),
        ContentPart.Image(ResourceRef("attachment:image-id", "image/png")),
        ContentPart.Resource(ResourceRef("attachment:document-id", "text/markdown")),
    )

    @Test
    fun `native identity restores original opaque references after profile store recreation`() = runTest {
        val disk = mutableMapOf<String, String>()
        val stores = SourceTestStores(disk)
        StoredPromptResourceHistory(stores).remember(session, "native-turn", parts)
        val reopened = StoredPromptResourceHistory(SourceTestStores(disk))
        assertEquals(parts, reopened.parts(session, "native-turn"))
        assertNull(reopened.parts(session, "another-turn"))
        assertNull(reopened.parts(session.copy(nativeId = "other-session"), "native-turn"))
        assertFalse(stores.store.spec.areValuesLogged)
        assertFalse(disk.keys.any { "private-session" in it || "native-turn" in it })
        assertFalse(disk.values.any { "base64" in it || "/private/" in it })
    }

    @Test
    fun `native paths encoded bytes and oversized source payloads cannot enter the sidecar`() = runTest {
        val disk = mutableMapOf<String, String>()
        val history = StoredPromptResourceHistory(SourceTestStores(disk))
        listOf("/private/image.png", "data:image/png;base64,aW1hZ2U=").forEach { reference ->
            assertFailsWith<IllegalArgumentException> {
                history.remember(session, "turn", listOf(ContentPart.Image(ResourceRef(reference, "image/png"))))
            }
        }
        assertFailsWith<IllegalArgumentException> { history.remember(session, "turn", List(11) { parts[1] }) }
        assertEquals(emptyMap(), disk)
    }
}

private class SourceTestStores(disk: MutableMap<String, String>) : DataStores {
    val store = SourceTestStore(disk)
    override val owner = StorageOwner.App
    override fun filesDirectory(name: String): String = error("Not used")
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Not used")
    override suspend fun fire(event: DataEvent) = Unit
}

private class SourceTestStore(private val disk: MutableMap<String, String>) : KeyValueStore {
    override var spec = KeyValueSpec("test_prompt_sources")
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = flow { emit(get(key)) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? {
        val type = key.type
        check(type is StoreValueType.Json)
        return disk[key.name]?.let { Json.decodeFromString(type.serializer, it) }
    }
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        val type = key.type
        check(type is StoreValueType.Json)
        disk[key.name] = Json.encodeToString(type.serializer, value)
    }
    override suspend fun remove(key: StoreKey<*>) {
        disk.remove(key.name)
    }
    override suspend fun clear() {
        disk.clear()
    }
}
