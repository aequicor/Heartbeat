package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.BindingStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.CachedModels
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ModelCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/** Profile key-value store of engine settings: bindings hold only ids, so values are safe to log. */
internal val EngineSettingsSpec = KeyValueSpec("aiengine_settings", areValuesLogged = true)
private val BindingsKey = jsonKey("bindings", ListSerializer(EngineBinding.serializer()))

/** Profile key-value store of discovered models; lists can be long, so values are not logged. */
internal val ModelCacheSpec = KeyValueSpec("aiengine_models")
private val ModelsKey = jsonKey("models", ListSerializer(CachedModels.serializer()))

/** [BindingStore] in the profile key-value store. */
class BindingStorage(private val store: KeyValueStore) : BindingStore {
    private val log = Log.tag("BindingStorage")

    override fun observe(): Flow<List<EngineBinding>> {
        log.d { "observe bindings" }
        return store.observe(BindingsKey).map { it.orEmpty() }
    }

    override suspend fun load(): List<EngineBinding> {
        log.d { "load bindings" }
        return store.get(BindingsKey).orEmpty()
    }

    override suspend fun save(bindings: List<EngineBinding>) {
        log.d { "save bindings count=${bindings.size}" }
        store.set(BindingsKey, bindings)
    }
}

/** [ModelCache] in the profile key-value store. */
class ModelCacheStorage(private val store: KeyValueStore) : ModelCache {
    private val log = Log.tag("ModelCacheStorage")

    override fun observe(): Flow<List<CachedModels>> {
        log.d { "observe model cache" }
        return store.observe(ModelsKey).map { it.orEmpty() }
    }

    override suspend fun load(): List<CachedModels> {
        log.d { "load model cache" }
        return store.get(ModelsKey).orEmpty()
    }

    override suspend fun save(entries: List<CachedModels>) {
        log.d { "save model cache entries=${entries.size}" }
        store.set(ModelsKey, entries)
    }
}
