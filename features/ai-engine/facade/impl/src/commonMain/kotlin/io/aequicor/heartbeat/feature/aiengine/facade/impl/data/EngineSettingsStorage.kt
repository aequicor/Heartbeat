package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.BindingStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineToggles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/** Profile key-value store of engine settings: bindings hold only ids, so values are safe to log. */
internal val EngineSettingsSpec = KeyValueSpec("aiengine_settings", areValuesLogged = true)
private val BindingsKey = jsonKey("bindings", ListSerializer(EngineBinding.serializer()))

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

/** [EngineToggles] over app feature toggles: the global [AiEngines] flag and the engine's own flag. */
class FeatureToggleEngineGate(private val toggles: FeatureToggles) : EngineToggles {
    private val log = Log.tag("EngineToggles")

    override fun observe(descriptor: EngineDescriptor): Flow<Boolean> =
        combine(toggles.observe(AiEngines), toggles.observe(descriptor.toggle)) { global, own -> global && own }
            .distinctUntilChanged()

    override suspend fun isEnabled(descriptor: EngineDescriptor): Boolean {
        val isEnabled = toggles.get(AiEngines) && toggles.get(descriptor.toggle)
        log.d { "engine toggle engine=${descriptor.id.value} enabled=$isEnabled" }
        return isEnabled
    }
}
