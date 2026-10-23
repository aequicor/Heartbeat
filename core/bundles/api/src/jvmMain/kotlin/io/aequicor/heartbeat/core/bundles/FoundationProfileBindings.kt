package io.aequicor.heartbeat.core.bundles

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens

/**
 * Stateful profile-level bindings the foundation hands to the profile graph of a bundle (`@Includes`). Read lazily:
 * a bundle graph is created while the foundation profile graph is still being attached. Like the app-level
 * [FoundationAppBindings], it lists only what bundles need; a bundle that needs more asks for it here.
 */
public interface FoundationProfileBindings {
    /** Identifier of the profile the graph belongs to. */
    public val profileId: ProfileId

    /** Scope of the profile: coroutines and cleanup of services that live as long as the profile. */
    @ForScope(ProfileScope::class)
    public val profileScope: ScopeHandle

    /** Stores of the profile's own data. */
    @ForScope(ProfileScope::class)
    public val dataStores: DataStores

    /** Studio screens that the Chromium side panel opens and drives (the studio stays in the foundation for now). */
    public val studioRemoteScreens: StudioRemoteScreens
}
