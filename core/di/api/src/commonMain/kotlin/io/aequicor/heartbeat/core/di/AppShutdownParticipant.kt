package io.aequicor.heartbeat.core.di

/**
 * App-scoped native resources that must finish asynchronous cleanup before the host exits its process.
 * Hosts await all participants before destroying the UI lifecycle. Implementations are idempotent,
 * reject new work once shutdown starts, and never initialize an unused resource just to shut it down.
 * This does not sign out the active profile or change its persisted selection.
 */
public fun interface AppShutdownParticipant {
    /** Flushes and closes owned resources; returns only after their native close callbacks finish. */
    public suspend fun shutdown()
}
