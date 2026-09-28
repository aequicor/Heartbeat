package io.aequicor.heartbeat.core.profilefacade

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.ProfileScope

/**
 * A profile-lifetime service started eagerly when the profile opens (e.g. a profile machine or a background
 * bridge that must run before any screen shows it). Contributed with `@ContributesIntoSet(ProfileScope::class)`;
 * [start] runs once on the main thread right after the profile graph is created and must not block.
 */
public fun interface ProfileStartup {
    /** Starts the service in its profile scope. */
    public fun start()
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
