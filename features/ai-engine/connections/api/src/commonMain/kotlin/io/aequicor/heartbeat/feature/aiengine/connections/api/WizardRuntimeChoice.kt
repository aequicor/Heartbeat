package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference

/** Explicit runtime choice and the owner token on which its isolated inspection was based. */
public data class WizardRuntimeChoice(
    val expected: LaunchSettingsSnapshot,
    val settings: LaunchSettings,
    val inspection: EngineCandidateInspection?,
) {
    override fun toString(): String = "WizardRuntimeChoice(***)"
}

/** Installation actions advertised for this engine on the current platform. */
public data class WizardRuntimeOptions(
    val isFilePickerAvailable: Boolean,
    val isManagedInstallAvailable: Boolean,
    val sources: Set<RuntimePreference> = emptySet(),
    val initialJob: EngineJob? = null,
)
