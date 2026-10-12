package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextSettingsStore

/** Context optimization settings in the profile key-value store, one JSON document. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueContextSettings(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : ContextSettingsStore {
    private val log = Log.tag("KeyValueContextSettings")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): ContextOptimizationSettings? = store.get(KEY).also {
        log.v { "read context settings: stored=${it != null}" }
    }

    override suspend fun save(settings: ContextOptimizationSettings) {
        log.v { "write context settings" }
        store.set(KEY, settings)
    }

    private companion object {
        val SPEC = KeyValueSpec("context_optimization")
        val KEY = jsonKey("settings", ContextOptimizationSettings.serializer())
    }
}
