package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeModelChecks : ModelChecks {
    val started = mutableListOf<Set<EngineTarget>>()
    val revoked = mutableListOf<Set<EngineTarget>>()
    val revokedBindings = mutableListOf<EngineBindingId>()
    var result: ModelCheckBatchResult<ModelSelection>? = null

    override fun observe(): Flow<List<ModelCheckReceipt>> = flowOf(emptyList())
    override fun current(receipts: List<ModelCheckReceipt>, context: ModelCheckContext): List<ModelCheckReceipt> =
        receipts
    override suspend fun saveAndCheck(
        targets: Set<EngineTarget>,
        onSaved: suspend () -> Unit,
        save: suspend () -> ModelSelection,
    ): ModelCheckBatchResult<ModelSelection> {
        started += targets
        val saved = save()
        onSaved()
        return result ?: ModelCheckBatchResult.Completed(saved, emptyList())
    }
    override fun revoke(targets: Set<EngineTarget>) {
        revoked += targets
    }
    override fun revoke(binding: EngineBindingId) {
        revokedBindings += binding
    }
}
