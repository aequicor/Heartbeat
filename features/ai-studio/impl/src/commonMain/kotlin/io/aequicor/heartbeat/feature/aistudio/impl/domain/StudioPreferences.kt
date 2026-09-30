package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion

/** Profile-owned start-page preferences. Native effort choices belong to the effort-configuration machine. */
internal interface StudioPreferences {
    /** Restores the saved model and approval policy, or uses [fallback] when no preference was saved. */
    suspend fun load(fallback: RunSettings): RunSettings

    /** Serializes writes in the profile scope and ignores stale revisions from the same workspace load. */
    suspend fun save(settings: RunSettings, version: StudioSettingsVersion)
}
