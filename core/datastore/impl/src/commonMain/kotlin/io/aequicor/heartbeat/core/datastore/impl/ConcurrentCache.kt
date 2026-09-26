package io.aequicor.heartbeat.core.datastore.impl

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Thread-safe map of lazily created values: [getOrPut] creates at most one value per key, even when called
 * concurrently (storages must have exactly one instance per file).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ConcurrentCache<K : Any, V : Any> {

    private val entries = AtomicReference<Map<K, Lazy<V>>>(emptyMap())

    fun getOrPut(key: K, create: () -> V): V {
        val candidate = lazy(create)
        while (true) {
            val current = entries.load()
            current[key]?.let { return it.value }
            if (entries.compareAndSet(current, current + (key to candidate))) return candidate.value
        }
    }

    /** Removes the entries matching [predicate]; returns their values that were created. */
    fun removeAll(predicate: (K) -> Boolean): List<V> {
        while (true) {
            val current = entries.load()
            val (removed, kept) = current.entries.partition { predicate(it.key) }
            if (entries.compareAndSet(current, kept.associate { it.key to it.value })) {
                return removed.mapNotNull { (_, lazy) -> lazy.takeIf { it.isInitialized() }?.value }
            }
        }
    }

    /** Values created so far. */
    fun values(): List<V> = entries.load().values.mapNotNull { lazy -> lazy.takeIf { it.isInitialized() }?.value }
}
