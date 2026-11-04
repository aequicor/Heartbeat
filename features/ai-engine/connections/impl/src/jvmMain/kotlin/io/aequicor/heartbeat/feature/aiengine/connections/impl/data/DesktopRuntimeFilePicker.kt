package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.RuntimeFilePicker

/** Opens the platform picker without running or changing the selected executable. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopRuntimeFilePicker(private val dialogs: DesktopFileDialogs) : RuntimeFilePicker {
    override val isAvailable: Boolean = true
    override suspend fun pick(): String? = dialogs.pickFiles(allowMultiple = false).singleOrNull()
}
