package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.AppShutdownParticipant

/** Desktop host awaits native flush/disposal before allowing Compose to terminate the JVM. */
@ContributesTo(AppScope::class)
interface AppShutdownAccessors {
    /** App-owned resources, including a browser runtime only if it has been used. */
    val shutdownParticipants: Set<AppShutdownParticipant>
}

/** The desktop can terminate normally before any native feature contributes cleanup. */
@ContributesTo(AppScope::class)
@BindingContainer
interface AppShutdownBindings {
    @Multibinds(allowEmpty = true)
    fun shutdownParticipants(): Set<AppShutdownParticipant>
}
