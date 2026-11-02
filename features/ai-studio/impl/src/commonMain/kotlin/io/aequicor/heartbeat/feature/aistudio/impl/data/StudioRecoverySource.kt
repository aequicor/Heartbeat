package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.Serializable

/** The source inspection remains historical evidence after Q changes from sender to observer of its own send. */
@Serializable
internal data class StudioRecoverySource(
    val request: RequestId,
    val turn: TurnId?,
    val outcome: StudioHelperTerminalOutcome?,
    val isIdle: Boolean,
)
