package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import kotlinx.coroutines.flow.Flow

/** Profile-owned start-page preferences. Native effort choices belong to the effort-configuration machine. */
internal interface StudioPreferences {
    /** Restores the saved model and approval policy, or uses [fallback] when no preference was saved. */
    suspend fun load(fallback: RunSettings): RunSettings

    /** Observes accepted model and approval choices, including values retained after a failed write. */
    fun observe(fallback: RunSettings): Flow<RunSettings>

    /** Serializes writes in the profile scope and ignores stale revisions from the same workspace load. */
    suspend fun save(settings: RunSettings, version: StudioSettingsVersion)
}
