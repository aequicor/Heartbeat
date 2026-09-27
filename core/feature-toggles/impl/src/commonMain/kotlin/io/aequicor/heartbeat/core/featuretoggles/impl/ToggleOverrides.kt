package io.aequicor.heartbeat.core.featuretoggles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Local overrides in the app key-value store [SPEC]: one record per overridden toggle, named by its key.
 * Values are logged by the callers (`FT`), so the store logs only key names.
 *
 * A stored value the declaration no longer accepts (a removed [FeatureToggle.Choice] option) reads as absent
 * (warned once per key). Storage failures propagate: [DataStoreFeatureToggles] falls back to defaults.
 * Before the first read it logs every current override once — the effective configuration of the session.
 */
@SingleIn(AppScope::class)
@Inject
internal class ToggleOverrides(
    @ForScope(AppScope::class) private val stores: DataStores,
    private val registry: ToggleRegistry,
) {
    private val log = Log.tag(FT_LOG_TAG)
    private val store by lazy { stores.keyValue(SPEC) }
    private val announceLock = Mutex()
    private val staleKeys = MutableStateFlow(emptySet<String>())

    @Volatile
    private var isAnnounced = false

    /** Override of [toggle] and its changes; `null` when not overridden. */
    fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T?> = flow {
        announce()
        emitAll(store.observe(toggle.storeKey()).map { toggle.accept(it) }.distinctUntilChanged())
    }

    /** Override of [toggle], or `null`. */
    suspend fun <T : Any> get(toggle: FeatureToggle<T>): T? {
        announce()
        return read(toggle)
    }

    /** Current state of [toggle]: its override or its default. */
    suspend fun <T : Any> state(toggle: FeatureToggle<T>): ToggleState<T> = toggle.stateOf(get(toggle))

    suspend fun <T : Any> set(toggle: FeatureToggle<T>, value: T) {
        store.set(toggle.storeKey(), value)
    }

    suspend fun remove(toggle: FeatureToggle<*>) {
        store.remove(toggle.storeKey())
    }

    /** Removes every stored override, including those of toggles that are no longer declared. */
    suspend fun clear() {
        store.clear()
    }

    private suspend fun <T : Any> read(toggle: FeatureToggle<T>): T? = toggle.accept(store.get(toggle.storeKey()))

    private suspend fun announce() {
        if (isAnnounced) return
        announceLock.withLock {
            if (isAnnounced) return
            val overridden = registry.toggles.mapNotNull { toggle -> read(toggle)?.let { "${toggle.key}=$it" } }
            isAnnounced = true
            log.i {
                if (overridden.isEmpty()) "no local overrides" else "local overrides: ${overridden.joinToString()}"
            }
        }
    }

    private fun <T : Any> FeatureToggle<T>.accept(stored: T?): T? {
        if (stored == null || this !is FeatureToggle.Choice || options.any { it == stored }) return stored
        if (key !in staleKeys.getAndUpdate { it + key }) {
            log.w { "$key: stored override '$stored' is not one of $options, using the default" }
        }
        return null
    }

    private companion object {
        val SPEC = KeyValueSpec("core_feature_toggles") // core_* names are reserved for the core
    }
}

/** State of [this] toggle with the [override] read from [ToggleOverrides]. */
internal fun <T : Any> FeatureToggle<T>.stateOf(override: T?): ToggleState<T> = if (override == null) {
    ToggleState(this, default, ToggleSource.Default)
} else {
    ToggleState(this, override, ToggleSource.LocalOverride)
}

// The key type follows the toggle type: Flag is a FeatureToggle<Boolean>, Choice a FeatureToggle<String>.
@Suppress("UNCHECKED_CAST")
private fun <T : Any> FeatureToggle<T>.storeKey(): StoreKey<T> = when (this) {
    is FeatureToggle.Flag -> booleanKey(key)
    is FeatureToggle.Choice -> stringKey(key)
} as StoreKey<T>
