package io.aequicor.heartbeat.feature.welcome.impl.domain

/**
 * Whether the welcome's secondary action opens the flag section of the unified settings window instead of the
 * separate flag panel.
 */
fun interface UnifiedSettingsPolicy {
    /** Current decision; reads the toggle from storage. */
    suspend fun isUnified(): Boolean
}
