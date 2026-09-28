package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogReasoningCatalogEnabled
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Resolved effort levels per model and the models whose provider rejected reasoning parameters. */
internal interface KoogReasoningStore {
    suspend fun read(): KoogReasoningState

    suspend fun write(state: KoogReasoningState)
}

/** Keys are `provider/model`. */
@Serializable
internal data class KoogReasoningState(
    val levels: Map<String, List<String>> = emptyMap(),
    val rejected: Set<String> = emptySet(),
)

/**
 * Decides which effort levels a Koog model offers. Order: a model whose provider once rejected reasoning parameters
 * offers none; the provider API (Anthropic, Ollama) is authoritative; otherwise the public catalog when its toggle
 * is on; otherwise the family guess. Discovery results are kept, so sessions validate without network calls.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class KoogReasoningLevels(
    private val store: KoogReasoningStore,
    private val catalog: KoogReasoningCatalog,
    private val toggles: FeatureToggles,
) {
    private val log = Log.tag("KoogReasoningLevels")
    private val mutex = Mutex()

    /** Resolves and remembers levels of [models]; [native] is what the provider API reported, if anything. */
    suspend fun discover(
        provider: KoogProvider,
        models: List<String>,
        native: Map<String, List<String>>?,
    ): Map<String, List<String>> = mutex.withLock {
        val state = store.read()
        val isCatalogUsed = native == null && toggles.get(KoogReasoningCatalogEnabled)
        val resolved = models.associateWith { model ->
            when {
                key(provider, model) in state.rejected -> emptyList()
                native != null -> native[model] ?: provider.fallbackReasoningEfforts(model)
                isCatalogUsed -> catalog.levels(provider, model) ?: provider.fallbackReasoningEfforts(model)
                else -> provider.fallbackReasoningEfforts(model)
            }
        }
        store.write(state.copy(levels = state.levels + resolved.mapKeys { key(provider, it.key) }))
        log.i { "reasoning levels resolved for ${models.size} models, source=${source(native, isCatalogUsed)}" }
        resolved
    }

    /** Levels of [model] from the last discovery, or the family guess when it was never discovered. */
    suspend fun levels(provider: KoogProvider, model: String): List<String> = mutex.withLock {
        val state = store.read()
        val key = key(provider, model)
        if (key in state.rejected) emptyList() else state.levels[key] ?: provider.fallbackReasoningEfforts(model)
    }

    /** Remembers that the provider refused reasoning parameters for [model]; it offers no levels from now on. */
    suspend fun reject(provider: KoogProvider, model: String) = mutex.withLock {
        val state = store.read()
        val key = key(provider, model)
        store.write(state.copy(rejected = state.rejected + key, levels = state.levels - key))
        log.w { "provider rejected reasoning parameters; effort disabled for this model" }
    }

    private fun key(provider: KoogProvider, model: String) = "${provider.id.value}/$model"

    private fun source(native: Map<String, List<String>>?, isCatalogUsed: Boolean) = when {
        native != null -> "provider"
        isCatalogUsed -> "catalog"
        else -> "fallback"
    }
}

/** Profile key-value storage of [KoogReasoningState]. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueKoogReasoningStore(
    @ForScope(ProfileScope::class) stores: DataStores,
) : KoogReasoningStore {
    private val log = Log.tag("KoogReasoningStore")
    private val store by lazy { stores.keyValue(Spec) }

    override suspend fun read(): KoogReasoningState {
        val state = store.get(Key) ?: KoogReasoningState()
        log.d { "read reasoning levels: ${state.levels.size}, rejected=${state.rejected.size}" }
        return state
    }

    override suspend fun write(state: KoogReasoningState) {
        log.d { "write reasoning levels: ${state.levels.size}, rejected=${state.rejected.size}" }
        store.set(Key, state)
    }

    private companion object {
        val Spec = KeyValueSpec("koog_reasoning_levels")
        val Key = jsonKey("state", KoogReasoningState.serializer())
    }
}
