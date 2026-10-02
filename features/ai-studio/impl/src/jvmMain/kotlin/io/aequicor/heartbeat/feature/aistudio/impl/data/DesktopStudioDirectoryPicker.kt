package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioDirectoryPicker

/** Folder of a local project, chosen in the host's own dialog (Explorer / Finder). */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopStudioDirectoryPicker(private val dialogs: DesktopFileDialogs) : StudioDirectoryPicker {
    private val log = Log.tag("StudioDirectoryPicker")
    override val isAvailable: Boolean = true

    override suspend fun pick(): String? {
        log.i { "Open local folder chooser" }
        return dialogs.pickDirectory()
    }
}
