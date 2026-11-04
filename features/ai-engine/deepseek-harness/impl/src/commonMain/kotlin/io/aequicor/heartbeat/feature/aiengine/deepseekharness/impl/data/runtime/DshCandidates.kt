package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import kotlin.time.TimeMark

/**
 * Platform support is explicit; an absent implementation never invents a fingerprint or a baseline for another
 * target. [promote] transfers ownership of an ordinary DSH runtime only for the exact assessed stamp, so the
 * candidate never constructs a runtime itself and admission never creates a native session.
 */
internal fun interface DshCandidates {
    suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate?
}

/** No platform implementation means no DSH candidate, not a guessed JVM contract. */
internal object UnsupportedDshCandidates : DshCandidates {
    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? = null
}
