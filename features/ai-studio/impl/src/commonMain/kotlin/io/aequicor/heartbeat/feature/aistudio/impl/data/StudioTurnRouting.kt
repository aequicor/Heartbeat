package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
/** Current chat configuration wins over next-chat preferences; disabled routes are refused. */
internal fun studioRunTarget(
    selected: ModelSelection,
    record: StudioChatRecord,
    applied: StudioSessionSettings?,
    settings: RunSettings,
): EngineTarget {
    val chosen = record.turnModelId(applied, settings.modelId)
    val target = requireNotNull(turnTarget(chosen, record.target, selected.defaultTarget)) {
        "Select a connected model in settings"
    }
    check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
    return target
}
