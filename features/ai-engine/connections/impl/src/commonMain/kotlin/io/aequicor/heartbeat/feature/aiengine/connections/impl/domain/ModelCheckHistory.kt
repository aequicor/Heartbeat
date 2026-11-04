package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import kotlinx.coroutines.flow.Flow

/** Internal storage port; observations never establish currentness or admission by themselves. */
internal interface ModelCheckHistory {
    fun observe(): Flow<List<ModelCheckReceipt>>

    /** Serialize writers and recheck ownership around storage IO; return false for a revoked publication. */
    suspend fun put(report: ModelCheckReport, isCurrent: suspend () -> Boolean = { true }): Boolean

    /** Cleanup preserves newer receipts accepted by [retain] after acquiring the serialized writer lock. */
    suspend fun remove(binding: EngineBindingId, retain: suspend (ModelCheckReceipt) -> Boolean = { false })
}
