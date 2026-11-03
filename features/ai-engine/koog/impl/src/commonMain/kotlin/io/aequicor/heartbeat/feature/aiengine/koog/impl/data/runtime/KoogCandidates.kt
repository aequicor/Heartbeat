package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import kotlin.time.TimeMark

/** Platform support is explicit; absent implementation never invents embedded build facts for another target. */
internal fun interface KoogCandidates {
    suspend fun open(request: EngineRuntimeCandidateRequest, deadline: TimeMark): EngineRuntimeCandidate?
}

/** No platform implementation means no candidate, not a guessed JVM contract. */
internal object UnsupportedKoogCandidates : KoogCandidates {
    override suspend fun open(request: EngineRuntimeCandidateRequest, deadline: TimeMark): EngineRuntimeCandidate? =
        null
}
