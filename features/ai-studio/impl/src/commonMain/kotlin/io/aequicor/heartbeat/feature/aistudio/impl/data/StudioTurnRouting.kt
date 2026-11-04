package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExactRouteReadiness
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAvailability
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings

/**
 * Current chat configuration wins over next-chat preferences; disabled or unconfirmed exact routes are refused.
 * [readiness] is a pure cached observation here: the send path never probes a route and never substitutes a
 * sibling binding, model or default target for one that disappeared.
 */
internal fun studioRunTarget(
    selected: ModelSelection,
    record: StudioChatRecord,
    applied: StudioSessionSettings?,
    settings: RunSettings,
    readiness: ExactRouteReadiness,
): EngineTarget {
    val chosen = record.turnModelId(applied, settings.modelId)
    val target = requireNotNull(turnTarget(chosen, record.target, selected.defaultTarget)) {
        "Select a connected model in settings"
    }
    check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
    if (readiness.availability(target) !is RouteAvailability.Available) {
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    }
    return target
}
