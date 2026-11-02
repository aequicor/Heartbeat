package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.serialization.Serializable

/** Actual submission configuration and human provenance; live profile security is deliberately not captured. */
@Serializable
internal data class StudioExecutionSnapshot(
    val settings: RunSettings,
    val target: EngineTarget,
    val reasoningEffort: String?,
    val reviewTarget: EngineTarget?,
    val authority: StudioHumanAuthority,
    val causes: Set<RequestInitiator> = emptySet(),
) {
    override fun toString(): String = "StudioExecutionSnapshot(***)"
}

/** Only the owning host's in-process capability may seed durable per-attempt authority. */
internal fun TrustedUserInstructions.savedAuthority(): StudioHumanAuthority = when (this) {
    is TrustedUserInstructions.Complete -> StudioHumanAuthority(
        messages.map { StudioHumanInstruction(it.request, it.text) },
    )

    TrustedUserInstructions.Incomplete -> StudioHumanAuthority(isComplete = false)
}

/** A future model choice is independent; changing engine or credentials requires an explicit context handoff. */
internal fun StudioExecutionSnapshot.requireContinuationConnection(record: StudioChatRecord) {
    check(record.pendingEngineSwitch == null) { "Continuation connection replacement is unresolved" }
    val current = checkNotNull(record.target) { "Continuation connection is unavailable" }
    check(current.engine == target.engine && current.binding == target.binding) {
        "Continuation engine or credential binding changed"
    }
}

/** Pure validation runs under the journal mutex before persisting actual configuration and route together. */
internal fun StudioHelperReceipt.freezeExecutionSnapshot(
    claim: String,
    snapshot: StudioExecutionSnapshot,
    nativeRoute: StudioRecoveryRoute?,
): StudioHelperReceipt {
    check(this.phase == StudioHelperPhase.Submitting && this.submissionClaim == claim) {
        "Execution snapshot belongs to another sender"
    }
    check(this.nativeEntryClaim == null) { "Execution snapshot must precede native entry" }
    check(this.executionSnapshot == null || this.executionSnapshot == snapshot) {
        "Execution snapshot cannot change"
    }
    check(this.causes == null || this.causes == snapshot.causes) { "Execution causes changed" }
    check(nativeRoute == null || nativeRoute.target == snapshot.target) { "Native route target changed" }
    check(this.nativeRoute == null || this.nativeRoute == nativeRoute) { "Native launch route changed" }
    return copy(executionSnapshot = snapshot, causes = snapshot.causes, nativeRoute = nativeRoute)
}
