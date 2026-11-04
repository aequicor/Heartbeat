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
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelCheckHistory
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
) : ModelCheckHistory {
    private val store = stores.keyValue(ModelSelectionSpec)
    private val mutex = Mutex()

    override fun observe(): Flow<List<ModelCheckReceipt>> = store.observe(
        ModelCheckReportsKey,
    ).map { bounded(it.orEmpty()) }

    /**
     * Latest receipt per exact target. Recheck the owner's run and full revision after acquiring the writer lock,
     * after reading, and after writing. A revoked in-flight write is removed before releasing that lock, so it
     * cannot overwrite a newer queued publication. Readers still filter by current ownership: a store may emit
     * the provisional value while set is pending, and storage failures do not establish currentness.
     */
    override suspend fun put(report: ModelCheckReport, isCurrent: suspend () -> Boolean): Boolean = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return@withLock false
        val kept = bounded(store.get(ModelCheckReportsKey).orEmpty()).filterNot {
            it.revision.target == report.revision.target
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return@withLock false
        currentCoroutineContext().ensureActive()
        store.set(ModelCheckReportsKey, (kept + ModelCheckReceipt.from(report)).takeLast(MAX_RECORDS))
        val isStillCurrent = isCurrent()
        if (!isStillCurrent) store.set(ModelCheckReportsKey, kept)
        isStillCurrent
    }

    /** Disconnect and rollback compensation remove all history for that binding, leaving choices untouched. */
    override suspend fun remove(binding: EngineBindingId, retain: suspend (ModelCheckReceipt) -> Boolean) =
        mutex.withLock {
            val kept = store.get(ModelCheckReportsKey).orEmpty().filter {
                it.revision.target.binding != binding || retain(it)
            }
            store.set(ModelCheckReportsKey, bounded(kept))
        }

    private fun bounded(reports: List<ModelCheckReceipt>): List<ModelCheckReceipt> =
        reports.asReversed().asSequence().distinctBy { it.revision.target }.take(MAX_RECORDS).toList().asReversed()

    companion object {
        const val MAX_RECORDS = 64
    }
}
