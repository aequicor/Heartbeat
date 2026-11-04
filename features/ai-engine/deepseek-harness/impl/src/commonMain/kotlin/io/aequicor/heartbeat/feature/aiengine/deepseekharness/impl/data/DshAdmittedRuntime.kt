package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime

/**
 * Post-promotion facts an ordinary DSH process must still prove against the assessed evidence: the ACP protocol it
 * negotiated, the executable it was started from and its own ACP agent metadata. The runtime line of the engine is
 * not this ACP agent version (DSH reports its adapter metadata there), so promotion observes the CLI line with the
 * adapter's existing `--version` probe instead. Reading these values is IO-free.
 */
internal data class DshProcessObservation(
    val agentVersion: String?,
    val protocol: EngineProtocolIdentity?,
    val selectedExecutable: String?,
)

/**
 * Ordinary DSH runtime bound to an assessed stamp. [observeOrdinaryStartup] starts one ordinary ACP process through
 * the same launch contract sessions use, completes only `initialize` and stops it, so promotion can compare the
 * negotiated protocol and the selected executable with the evidence without creating a session or sending a prompt.
 */
internal interface DshAdmittedRuntime : EngineRuntime {
    val selectedExecutable: String?

    suspend fun observeOrdinaryStartup(): DshProcessObservation?
}
