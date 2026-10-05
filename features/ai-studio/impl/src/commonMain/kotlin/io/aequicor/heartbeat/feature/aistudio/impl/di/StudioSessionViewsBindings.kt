package io.aequicor.heartbeat.feature.aistudio.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aistudio.impl.data.StudioSessionViewer
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionViews

/** Shares one native reader and transcript store between profile recording and screen views. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object StudioSessionViewsBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun viewer(
        facade: EngineFacade,
        @ForScope(ProfileScope::class) stores: DataStores,
    ): StudioSessionViewer = StudioSessionViewer(facade, stores)

    @Provides
    internal fun views(viewer: StudioSessionViewer): StudioSessionViews = viewer
}
