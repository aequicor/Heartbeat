package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchFailure
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchFailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedNative

internal fun StudioEngineSwitchState.toUi(): EngineSwitchUi = EngineSwitchUi(
    isPending,
    when (failure) {
        null -> null
        StudioEngineSwitchFailure.Disabled -> EngineSwitchFailureUi.Disabled
        StudioEngineSwitchFailure.Busy -> EngineSwitchFailureUi.Busy
        StudioEngineSwitchFailure.Stale -> EngineSwitchFailureUi.Stale
        StudioEngineSwitchFailure.Unsupported -> EngineSwitchFailureUi.Unsupported
        StudioEngineSwitchFailure.Unavailable -> EngineSwitchFailureUi.Unavailable
    },
)

/** Menu snapshots carry their source route so a delayed remote action cannot replace a newer route. */
internal fun sessionSettingIntent(
    id: String,
    change: StudioSettingChange,
    expectedModelId: String,
    isSwitchingAvailable: Boolean,
): AiStudioIntent.Public {
    val source = studioModelTarget(expectedModelId)
    val target = (change as? StudioSettingChange.Model)?.modelId?.let(::studioModelTarget)
    if (!isSwitchingAvailable || source == null || target == null) {
        return AiStudioIntent.Public.ChangeSessionSetting(id, change)
    }
    return if (source.engine != target.engine || source.binding != target.binding) {
        AiStudioIntent.Public.SwitchSessionEngine(id, StudioEngineSwitch(source, target))
    } else {
        AiStudioIntent.Public.ChangeSessionSetting(id, change)
    }
}

/** A menu snapshot belongs to its original pane content; excluded chats never change native settings. */
internal fun AiStudioScreenState.acceptsSettingSelection(pane: PaneUi, expectedSessionId: String?): Boolean =
    (expectedSessionId == null || pane.sessionId.orEmpty() == expectedSessionId) &&
        organisms[pane.sessionId] == null && session(pane.sessionId)?.isOrganism != true &&
        selectedNative(pane.sessionId) == PRIMARY_SUB_SESSION
