package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.intKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User limit of parallel builds. Capacity is a property of the machine, so the value is stored app-wide and shared
 * by every profile; null means the automatic CPU/memory estimate.
 */
@Inject
@SingleIn(AppScope::class)
internal class BuildCapacitySettings(
    @ForScope(AppScope::class) private val stores: Lazy<DataStores>,
) {
    private val log = Log.tag("BuildCapacitySettings")
    private val store by lazy { stores.value.keyValue(SPEC) }

    fun observe(): Flow<Int?> = store.observe(KEY).map { it.normalized() }

    suspend fun set(parallelBuilds: Int?) {
        val value = parallelBuilds.normalized()
        log.i { "Build capacity limit changed parallelBuilds=${value ?: "auto"}" }
        if (value == null) store.remove(KEY) else store.set(KEY, value)
    }

    private fun Int?.normalized(): Int? = this?.takeIf { it > 0 }?.coerceAtMost(BuildBudget.MAX_PARALLEL_BUILDS)

    private companion object {
        val SPEC = KeyValueSpec("worktree_build_capacity")
        val KEY = intKey("parallel_builds")
    }
}
