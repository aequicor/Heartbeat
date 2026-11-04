package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import kotlin.time.TimeMark

/**
 * Platform support is explicit; an absent implementation never invents an artifact or a baseline for another
 * target. [promote] transfers ownership of an ordinary Kimi runtime only for the exact assessed stamp, so the
 * candidate never constructs a runtime itself and admission never creates a native session.
 */
internal fun interface KimiCandidates {
    suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate?
}

/** No platform implementation means no Kimi candidate, not a guessed JVM contract. */
internal object UnsupportedKimiCandidates : KimiCandidates {
    override suspend fun open(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
        promote: suspend (String?, EngineCompatibilityStamp) -> EngineRuntime,
    ): EngineRuntimeCandidate? = null
}
