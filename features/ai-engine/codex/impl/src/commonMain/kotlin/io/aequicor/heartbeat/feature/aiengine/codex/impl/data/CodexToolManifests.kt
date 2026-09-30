package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.datastore.stringSetKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Exact versioned declarations saved for threads created with Heartbeat hosted tools. */
internal interface CodexToolManifests {
    suspend fun get(id: String): String?
    suspend fun isRequired(id: String): Boolean
    suspend fun save(id: String, manifest: String)
}

/** Fixtures without profile storage still track their own created threads. */
internal class MemoryCodexToolManifests : CodexToolManifests {
    private val values = mutableMapOf<String, String>()
    override suspend fun isRequired(id: String): Boolean = id in values
    override suspend fun get(id: String): String? = values[id]
    override suspend fun save(id: String, manifest: String) {
        values[id] = manifest
    }
}

private val ManifestSpec = KeyValueSpec("ai_codex_hosted_tools")
private val ThreadsKey = jsonKey("threads", JsonObject.serializer())
private val RequiredKey = stringSetKey("required")

/** Profile storage retains schemas across app-server restarts without modifying native rollouts. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class StoredCodexToolManifests(
    @ForScope(ProfileScope::class) stores: DataStores,
) : CodexToolManifests {
    private val store = stores.keyValue(ManifestSpec)
    private val mutex = Mutex()
    override suspend fun get(id: String): String? = (store.get(ThreadsKey)?.get(id) as? JsonPrimitive)?.content
    override suspend fun isRequired(id: String): Boolean = id in store.get(RequiredKey).orEmpty()
    override suspend fun save(id: String, manifest: String) = mutex.withLock {
        // Separate marker fails closed if the schema record is lost or becomes undecodable.
        store.set(RequiredKey, store.get(RequiredKey).orEmpty() + id)
        val before = store.get(ThreadsKey).orEmpty()
        store.set(ThreadsKey, JsonObject(before + (id to JsonPrimitive(manifest))))
    }
}
