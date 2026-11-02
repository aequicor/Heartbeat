package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.Flow

/** Internal machine-effect port. Returned execution reports remain provisional; observe supplies current receipts. */
internal interface ModelChecks {
    fun observe(): Flow<List<ModelCheckReceipt>>
    suspend fun saveAndCheck(
        targets: Set<EngineTarget>,
        onSaved: suspend () -> Unit = {},
        save: suspend () -> ModelSelection,
    ): ModelCheckBatchResult<ModelSelection>
    fun revoke(targets: Set<EngineTarget>)
    fun revoke(binding: EngineBindingId)
}
