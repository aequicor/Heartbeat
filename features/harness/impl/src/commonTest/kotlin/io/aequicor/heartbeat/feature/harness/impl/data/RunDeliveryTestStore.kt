package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant

internal class RunDeliveryClock(var instant: Instant = Instant.fromEpochSeconds(1_000)) : Clock {
    override fun now(): Instant = instant
}

internal class RunDeliveryTestStore(val clock: RunDeliveryClock) : KeyValueStore {
    override val spec = KeyValueSpec("harness_test")
    val values = mutableMapOf<String, Any>()
    val retention = mutableMapOf<String, Retention>()
    var crashAfterWrite: ((String, Any?) -> Boolean)? = null
    var isUnavailable = false
    var writes = 0
    var readFailureKey: String? = null
    var readFailure: Exception = IllegalStateException("private-store-message")

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = flow { emit(get(key)) }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(key: StoreKey<T>): T? {
        check(!isUnavailable)
        if (key.name == readFailureKey) throw readFailure
        val expires = (retention[key.name]?.expiry as? Expiry.At)?.instant
        return if (expires != null && expires <= clock.now()) null else values[key.name] as T?
    }

    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        check(!isUnavailable)
        values[key.name] = value
        this.retention[key.name] = retention
        written(key.name, value)
    }

    override suspend fun remove(key: StoreKey<*>) {
        check(!isUnavailable)
        values.remove(key.name)
        retention.remove(key.name)
        written(key.name, null)
    }

    override suspend fun clear() {
        values.clear()
        retention.clear()
    }

    private fun written(key: String, value: Any?) {
        writes++
        if (crashAfterWrite?.invoke(key, value) == true) {
            isUnavailable = true
            error("Simulated owner loss")
        }
    }
}
