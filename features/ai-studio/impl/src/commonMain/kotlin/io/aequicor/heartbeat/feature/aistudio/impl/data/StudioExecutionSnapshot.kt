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
