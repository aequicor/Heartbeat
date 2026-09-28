package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioDirectoryPicker

@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedStudioDirectoryPicker : StudioDirectoryPicker {
    override val isAvailable: Boolean = false
    override suspend fun pick(): String? = error("Local CLI folders are unavailable on this platform")
}
