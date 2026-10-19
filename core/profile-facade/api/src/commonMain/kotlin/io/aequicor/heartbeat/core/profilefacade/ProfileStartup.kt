package io.aequicor.heartbeat.core.profilefacade

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.ProfileScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A profile-lifetime service started eagerly when the profile opens (e.g. a profile machine or a background
 * bridge that must run before any screen shows it). Contributed with `@ContributesIntoSet(ProfileScope::class)`;
 * [start] runs once on the main thread right after the profile graph is created and must not block.
 */
public fun interface ProfileStartup {
    /** Starts the service in its profile scope. */
    public fun start()

    /**
     * Waits for the initial recovery snapshot, not completion of running work. Strict hosts bound this wait and
     * do not admit themselves as ready on failure. Ordinary UI hosts keep the non-blocking [start] contract.
     */
    public suspend fun awaitReady(): Unit = Unit
}

/** Declares the (possibly empty) set of [ProfileStartup]s of a profile. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public interface ProfileStartupBindings {
    /** Services started with the profile; empty until a feature contributes one. */
    @Multibinds(allowEmpty = true)
    public fun profileStartups(): Set<ProfileStartup>
}

/** Accessor of the profile graph; `ProfileSessions` starts these services after creating the graph. */
@ContributesTo(ProfileScope::class)
public interface ProfileStartupAccess {
    /** Services started with the profile. */
    public val profileStartups: Set<ProfileStartup>
}

/** Strict host recovery barrier; the caller bounds it and owns shutdown on any failure. No services are restarted. */
public suspend fun ProfileSession.awaitStartupReady(): Unit = coroutineScope {
    check(startupFailures == 0) { "Profile startup failed" }
    startups.map { startup -> async { startup.awaitReady() } }.awaitAll()
    Unit
}
