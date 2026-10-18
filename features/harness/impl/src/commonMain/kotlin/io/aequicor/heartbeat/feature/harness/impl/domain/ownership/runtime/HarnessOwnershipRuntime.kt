package io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord

/** Profile-owned admission. A marker cannot create authority; protected bindings remain effective without it. */
internal interface HarnessOwnershipRuntime {
    suspend fun binding(owner: HarnessId, record: ProcessRecord): OwnershipLocalBinding?
    suspend fun hasBindings(owner: HarnessId): Boolean
    suspend fun isBound(local: OwnershipLocalProcess): Boolean
    suspend fun enter(
        owner: HarnessId,
        record: ProcessRecord,
        kind: OwnershipPermitKind,
        correlation: String,
    ): NativeOwnershipAdmission
    suspend fun find(
        owner: HarnessId,
        record: ProcessRecord,
        kind: OwnershipPermitKind,
        correlation: String,
    ): OwnershipNativeIntent?
    suspend fun complete(intent: OwnershipNativeIntent): Boolean
    suspend fun isCurrent(intent: OwnershipNativeIntent): Boolean
}

/** Only Fresh permits dispatch once. Recover observes existing native work; it never creates another producer. */
internal sealed interface NativeOwnershipAdmission {
    data object Legacy : NativeOwnershipAdmission
    data class Fresh(val intent: OwnershipNativeIntent) : NativeOwnershipAdmission
    data class Recover(val intent: OwnershipNativeIntent) : NativeOwnershipAdmission
    data object Unavailable : NativeOwnershipAdmission
}
