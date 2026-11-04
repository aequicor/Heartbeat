package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot

/** Selection and observation only. A separate conditional commit and runtime admission are still required. */
internal sealed interface RuntimeFileSelection {
    data object Cancelled : RuntimeFileSelection
    data object Unavailable : RuntimeFileSelection

    data class Selected(
        val expected: LaunchSettingsSnapshot,
        val settings: LaunchSettings,
        val inspection: EngineCandidateInspection?,
    ) : RuntimeFileSelection {
        override fun toString(): String = "RuntimeFileSelection.Selected(***)"
    }
}

/** Captures the owner token before opening the dialog so a concurrent change invalidates the inspection. */
internal suspend fun selectRuntimeFile(
    engine: EngineId,
    management: EngineManagement,
    picker: RuntimeFilePicker,
): RuntimeFileSelection {
    if (!picker.isAvailable) return RuntimeFileSelection.Unavailable
    val expected = management.snapshotLaunchSettings(engine)
    val path = picker.pick() ?: return RuntimeFileSelection.Cancelled
    val settings = expected.settings.copy(executable = path)
    val inspection = management.inspectCandidate(engine, expected, settings)
    return RuntimeFileSelection.Selected(expected, settings, inspection)
}
