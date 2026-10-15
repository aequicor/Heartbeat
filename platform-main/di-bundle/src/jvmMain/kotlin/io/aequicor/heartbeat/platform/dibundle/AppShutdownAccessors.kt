package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Desktop host awaits native flush/disposal before allowing Compose to terminate the JVM. */
@ContributesTo(AppScope::class)
interface AppShutdownAccessors {
    /** App-owned resources, including a browser runtime only if it has been used. */
    val shutdownParticipants: Set<AppShutdownParticipant>

    /** Root owner; closing it cascades to all profiles without changing the persisted profile selection. */
    @ForScope(AppScope::class)
    val appScope: ScopeHandle
}

/**
 * Closes the application and all nested scopes after native participants finish, then awaits cancellation cleanup.
 * The desktop host supplies the time bound and keeps its event loop alive until this suspending barrier completes.
 * Unlike signing out through ProfileSessions, this preserves the active profile for the next launch.
 */
suspend fun AppShutdownAccessors.closeScopes() {
    try {
        (appScope as OwnedScope).close()
    } finally {
        withContext(NonCancellable) { appScope.coroutineScope.coroutineContext[Job]?.join() }
    }
}

/** The desktop can terminate normally before any native feature contributes cleanup. */
@ContributesTo(AppScope::class)
@BindingContainer
interface AppShutdownBindings {
    @Multibinds(allowEmpty = true)
    fun shutdownParticipants(): Set<AppShutdownParticipant>
}
