package io.aequicor.heartbeat.feature.searchengine.impl.domain

/**
 * Whether the unified settings window replaces the profile search settings opened outside it:
 * such a route then moves to its settings section; otherwise it keeps its own screen with "back".
 */
fun interface UnifiedSettingsPolicy {
    /** Current decision; reads the toggle from storage. */
    suspend fun isUnified(): Boolean
}
