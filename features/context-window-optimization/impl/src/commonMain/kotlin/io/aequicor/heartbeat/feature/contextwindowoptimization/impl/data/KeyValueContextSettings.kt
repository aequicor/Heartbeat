package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedger
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextSettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

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

    override suspend fun loadLedgers(): Map<SessionRef, ContextLedger> = store.get(LEDGERS).orEmpty()
        .associate { it.session to it.ledger }
        .also { log.v { "read ledgers: ${it.size}" } }

    override suspend fun saveLedgers(ledgers: Map<SessionRef, ContextLedger>) {
        log.v { "write ledgers: ${ledgers.size}" }
        store.set(LEDGERS, ledgers.map { (session, ledger) -> StoredLedger(session, ledger) })
    }

    private companion object {
        val SPEC = KeyValueSpec("context_optimization")
        val KEY = jsonKey("settings", ContextOptimizationSettings.serializer())
        val LEDGERS = jsonKey("ledgers", ListSerializer(StoredLedger.serializer()))
    }
}

/** One stored ledger; the ledger text is user data and never logged. */
@Serializable
private data class StoredLedger(val session: SessionRef, val ledger: ContextLedger)
