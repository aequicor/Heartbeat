package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind

/** Classes a failure for the transcript notice; unclassified or absent ones stay [RunFailureKind.Unknown]. */
internal fun EngineFailure?.toRunFailureKind(): RunFailureKind = when (this) {
    is EngineFailure.RateLimited, is EngineFailure.QuotaExceeded -> RunFailureKind.Limit
    is EngineFailure.ContextLimitExceeded -> RunFailureKind.Context
    is EngineFailure.Authentication -> RunFailureKind.Authentication
    is EngineFailure.Transport -> RunFailureKind.Network
    else -> RunFailureKind.Unknown
}
