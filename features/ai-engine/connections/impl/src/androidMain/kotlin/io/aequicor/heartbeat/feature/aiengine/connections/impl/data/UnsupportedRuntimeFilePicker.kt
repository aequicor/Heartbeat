package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.RuntimeFilePicker

@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedRuntimeFilePicker : RuntimeFilePicker {
    override val isAvailable: Boolean = false
    override suspend fun pick(): String? = null
}
