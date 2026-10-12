package io.aequicor.heartbeat.core.di

/**
 * App-scoped native resources that must finish asynchronous cleanup before the host exits its process.
 * Only the desktop host awaits participants: it runs them in parallel within an overall bound before destroying
 * the UI lifecycle, and exits even when one fails or times out (the failure is logged). Android and iOS never
 * call them; the OS ends the process. Implementations are idempotent, allow a retry after a failed or timed-out
 * call, reject new work once shutdown starts, and never initialize an unused resource just to shut it down.
 * This does not sign out the active profile or change its persisted selection.
 */
public fun interface AppShutdownParticipant {
    /** Flushes and closes owned resources; returns only after their native close callbacks finish. */
    public suspend fun shutdown()
}
