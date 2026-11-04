package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import kotlin.time.TimeMark

/**
 * Platform support is explicit; an absent implementation never invents an artifact or a baseline for another
 * target. [promote] transfers ownership of an ordinary Codex runtime only for the exact assessed stamp, so the
 * candidate never constructs a runtime itself.
 */
internal fun interface CodexCandidates {
    suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate?
}

/** No platform implementation means no Codex candidate, not a guessed JVM contract. */
internal object UnsupportedCodexCandidates : CodexCandidates {
    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? = null
}
