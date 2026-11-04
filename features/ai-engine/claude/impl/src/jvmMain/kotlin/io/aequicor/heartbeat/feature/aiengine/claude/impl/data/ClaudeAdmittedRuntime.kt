package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime

/**
 * Post-promotion facts the ordinary pinned CLI must still prove against the assessed evidence: the version its own
 * `--version` process reported and the executable that process was started from. A promoted runtime that cannot
 * report both is not bound to the evidence and must not be admitted.
 */
internal interface ClaudeAdmittedRuntime : EngineRuntime {
    val observedVersion: String?

    val selectedExecutable: String?
}

/** Admission facts retained by an ordinary runtime; null for a runtime created outside admission. */
internal data class ClaudeRuntimeAdmission(
    val stamp: EngineCompatibilityStamp,
    val observedVersion: String?,
    val selectedExecutable: String?,
)
