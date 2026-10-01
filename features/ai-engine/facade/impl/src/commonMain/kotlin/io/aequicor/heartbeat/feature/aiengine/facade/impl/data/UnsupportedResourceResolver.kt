package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver

/** Bundles without a host attachment store still resolve engine-native references themselves. */
@ContributesBinding(ProfileScope::class, priority = 0)
@Inject
internal class UnsupportedResourceResolver : ResourceResolver {
    override suspend fun resolve(reference: ResourceRef): ResolvedResource? = null
}
