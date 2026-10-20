package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome

/**
 * Remote runtimes require fresh inspection. Adapters without that capability expose authoritative Ready state;
 * require its retained terminal receipt, never infer completion from an empty Ready or an unknown outcome.
 * Claude and Koog restore interrupted turns as Unknown, so restarting cannot turn lost acceptance into permission.
 */
internal suspend fun ActiveSession.isEngineSwitchIdle(): Boolean = when (
    val access = features.resolve(
        RestoresSessionTurns,
    )
) {
    is FeatureAccess.Available -> access.feature.inspect(null) == TurnInspection.Idle

    is FeatureAccess.Unavailable -> false

    FeatureAccess.Unsupported -> {
        val outcome = (state.value as? ActiveSessionState.Ready)?.lastTurn?.outcome
        outcome != null && outcome != TurnOutcome.Unknown
    }
}
