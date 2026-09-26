package io.aequicor.heartbeat.core.profilefacade

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedScopes

/**
 * Graph of [ProfileScope]. Generated together with the app graph in `:platform-main:di-bundle`,
 * so it contains every contribution to `ProfileScope` from all modules.
 *
 * Platform code reaches profile-level entry points through accessor interfaces contributed to
 * `ProfileScope` (`(graph as HomeAccessors).home`): Metro makes the generated graph implement them.
 */
@GraphExtension(ProfileScope::class)
public interface ProfileGraph {
    /** Handle of the profile scope. */
    @ForScope(ProfileScope::class)
    public val scope: ScopeHandle

    /** Shared objects of this profile. */
    public val sharedScopes: SharedScopes

    /** Creates the profile graph. Only `ProfileSessions` may call it — review rule, not enforced by the compiler. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    public fun interface Factory {
        /** Creates the graph of profile [id] bound to its [scope]. */
        public fun create(
            @Provides id: ProfileId,
            @Provides @ForScope(ProfileScope::class) scope: ScopeHandle,
        ): ProfileGraph
    }
}
