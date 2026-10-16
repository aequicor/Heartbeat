package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioDirectoryPicker
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Folder of a local project, chosen in the host's own dialog (Explorer / Finder). */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopStudioDirectoryPicker(
    private val dialogs: DesktopFileDialogs,
    private val dispatchers: DispatcherProvider,
) : StudioDirectoryPicker {
    private val log = Log.tag("StudioDirectoryPicker")
    override val isAvailable: Boolean = true

    override suspend fun pick(): String? {
        log.i { "Open local folder chooser" }
        return dialogs.pickDirectory()
    }

    /** A `.git` directory or worktree file in the folder or any parent marks a Git working tree. */
    override suspend fun isRepository(directory: String): Boolean = withContext(dispatchers.io) {
        generateSequence(Path.of(directory).toAbsolutePath().normalize()) { it.parent }
            .any { Files.exists(it.resolve(GIT_MARKER)) }
    }

    private companion object {
        const val GIT_MARKER = ".git"
    }
}
