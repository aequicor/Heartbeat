package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

internal val ModelCheckReportsKey: StoreKey<List<ModelCheckReceipt>> =
    jsonKey("model_check_reports", ListSerializer(ModelCheckReceipt.serializer()))

/**
 * Bounded profile history of observations, not currentness/admission state. The coordinator must revalidate route,
 * revision and run before publication and before presenting a stored report. Merely reading never runs a probe.
 * Separate from legacy choices so corruption/eviction of this key cannot invalidate a selected/default model.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StoredModelCheckReports(
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val store = stores.keyValue(ModelSelectionSpec)
    private val mutex = Mutex()

    fun observe(): Flow<List<ModelCheckReceipt>> = store.observe(ModelCheckReportsKey).map { bounded(it.orEmpty()) }

    /** Latest published receipt per exact target; the caller excludes obsolete runs without relying on wall clock. */
    suspend fun put(report: ModelCheckReport) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val kept = bounded(store.get(ModelCheckReportsKey).orEmpty()).filterNot {
            it.revision.target == report.revision.target
        }
        currentCoroutineContext().ensureActive()
        store.set(ModelCheckReportsKey, (kept + ModelCheckReceipt.from(report)).takeLast(MAX_RECORDS))
    }

    /** Disconnect and rollback compensation remove all history for that binding, leaving choices untouched. */
    suspend fun remove(binding: EngineBindingId) = mutex.withLock {
        val kept = store.get(ModelCheckReportsKey).orEmpty().filterNot { it.revision.target.binding == binding }
        store.set(ModelCheckReportsKey, bounded(kept))
    }

    private fun bounded(reports: List<ModelCheckReceipt>): List<ModelCheckReceipt> =
        reports.asReversed().asSequence().distinctBy { it.revision.target }.take(MAX_RECORDS).toList().asReversed()

    companion object {
        const val MAX_RECORDS = 64
    }
}
