package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import kotlin.time.TimeMark

/**
 * Platform support is explicit; an absent implementation never invents an artifact or a baseline for another
 * target. [promote] receives the exact executable the candidate verified together with the assessed stamp, so it
 * can compare that pin with the launch it is about to start before any ordinary process opens. Ownership of the
 * runtime transfers only for the exact assessed stamp; the candidate never constructs a runtime itself.
 */
internal fun interface CodexCandidates {
    suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate?
}

/** No platform implementation means no Codex candidate, not a guessed JVM contract. */
internal object UnsupportedCodexCandidates : CodexCandidates {
    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? = null
}
