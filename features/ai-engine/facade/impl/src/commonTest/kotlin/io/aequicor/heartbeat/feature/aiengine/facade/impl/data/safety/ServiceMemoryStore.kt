package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Exercises production serialization and restart reads, including corrupt data and ambiguous write ACKs. */
internal class ServiceMemoryStore : KeyValueStore {
    override val spec = ServiceTurnSpec
    val raw = MutableStateFlow<String?>(null)
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    override fun <T : Any> observe(key: StoreKey<T>) = raw.map { decode(key, it) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = decode(key, raw.value)
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        beforeWrite()
        raw.value = Json.encodeToString((key.type as StoreValueType.Json<T>).serializer, value)
        afterWrite()
    }
    override suspend fun remove(key: StoreKey<*>) {
        raw.value = null
    }
    override suspend fun clear() {
        raw.value = null
    }
    private fun <T : Any> decode(key: StoreKey<T>, text: String?): T? =
        text?.let { Json.decodeFromString((key.type as StoreValueType.Json<T>).serializer, it) }
}

internal fun testServiceJournal() = ServiceTurnJournal(ServiceMemoryStore())
