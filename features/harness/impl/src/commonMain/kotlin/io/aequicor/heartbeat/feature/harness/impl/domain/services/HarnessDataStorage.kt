package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataSnapshot
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** Profile-owned transactions; [authorize] is checked again after waiting for the writer and before commit. */
internal interface HarnessDataStorage {
    fun revision(owner: HarnessId): Flow<Long>
    suspend fun read(owner: HarnessId, key: String, authorize: () -> Unit): ScriptDataSnapshot
    suspend fun write(
        owner: HarnessId,
        key: String,
        value: JsonElement?,
        expectedRevision: Long,
        authorize: () -> Unit,
    ): ScriptDataWriteResult

    /** Removes namespaces whose harness deletion has committed; retrying this operation is harmless. */
    suspend fun retainOwners(owners: Set<HarnessId>)
}
