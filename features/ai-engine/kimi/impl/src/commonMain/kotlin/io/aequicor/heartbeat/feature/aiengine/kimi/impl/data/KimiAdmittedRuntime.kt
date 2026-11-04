package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime

/**
 * Post-promotion facts the ordinary Kimi process must still prove against the assessed evidence: the runtime
 * version its own ACP `initialize` answered with, the negotiated protocol identity, and the executable it was
 * started from. A promoted runtime that cannot report all three is not bound to the evidence and must not be
 * admitted. Reading these values is IO-free.
 */
internal interface KimiAdmittedRuntime : EngineRuntime {
    val observedRuntimeVersion: String?

    val observedProtocol: EngineProtocolIdentity?

    val selectedExecutable: String?
}
