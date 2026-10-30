package io.aequicor.heartbeat.feature.aistudio.impl.domain

/**
 * Host-only admission of a prepared turn. The host atomically validates and claims its delivery in [admit].
 * False or cancellation refuses submission. Admission permits delivery but does not prove engine or organism
 * acceptance; the runtime reports that separately through onAccepted. Native work retains profile ownership after
 * admission. This callback is neither persisted nor sent to engines.
 */
fun interface StudioRunAdmission {
    /** Called at the submission boundary after preparation, at most once per run. */
    suspend fun admit(): Boolean
}
