package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope

@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedWorkspaceDirectories : WorkspaceDirectories {
    override val isAvailable: Boolean = false
    override suspend fun canonical(directory: String): WorkspaceDirectory? =
        throw UnsupportedOperationException("Local projects are unavailable on this platform")
}
