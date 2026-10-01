package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import kotlinx.coroutines.flow.Flow

/** Profile permission to use the computer, with automatic frame defaults for hosted tools. */
internal data class ComputerUseSettings(
    val preset: String = DEFAULT_PRESET,
    val isCursorIncluded: Boolean = true,
    val isEnabled: Boolean = false,
) {
    /** The encoding the chosen preset stands for; an unknown name falls back to the agent overview. */
    val encoding: CaptureEncoding get() = CapturePresets.byName(preset) ?: CapturePresets.AgentOverview

    internal companion object {
        /** Name of the preset the panel and the hosted tools use until the profile chooses another one. */
        const val DEFAULT_PRESET: String = "overview"
    }
}

/**
 * Profile-owned computer use settings.
 *
 * Only names and flags are stored — never frames, paths or window titles.
 */
internal interface ComputerUsePreferences {
    /** Current settings. */
    suspend fun read(): ComputerUseSettings

    /** Current settings and their changes. */
    fun observe(): Flow<ComputerUseSettings>

    /** Enables or disables computer tools for this profile; disabled until explicitly enabled. */
    suspend fun setEnabled(isEnabled: Boolean)

    /** Chooses the frame preset by name; an unknown name is refused. */
    suspend fun setPreset(name: String): Boolean

    /** Chooses whether the pointer is drawn into captured frames. */
    suspend fun setCursorIncluded(isIncluded: Boolean)
}
