package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime

/**
 * Post-promotion facts the ordinary Codex metadata process must still prove against the assessed evidence:
 * the version its own `initialize` handshake reported and the executable it was started from. A promoted runtime
 * that cannot report both is not bound to the evidence and must not be admitted.
 */
internal interface CodexAdmittedRuntime : EngineRuntime {
    val handshakeVersion: String?

    val selectedExecutable: String?
}
