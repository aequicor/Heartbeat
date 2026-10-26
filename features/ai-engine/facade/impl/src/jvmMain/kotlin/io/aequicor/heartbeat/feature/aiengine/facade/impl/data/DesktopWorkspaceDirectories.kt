package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopWorkspaceDirectories(private val dispatchers: DispatcherProvider) : WorkspaceDirectories {
    private val log = Log.tag("LocalWorkspaceDirectory")
    override val isAvailable: Boolean = true

    override suspend fun canonical(directory: String): WorkspaceDirectory? = withContext(dispatchers.io) {
        log.v { "Validating local project directory" }
        try {
            val input = Path.of(directory)
            if (!input.isAbsolute) return@withContext null
            if (Files.notExists(input, LinkOption.NOFOLLOW_LINKS)) {
                // A stored project whose folder is gone is routine and re-checked often; no stack trace.
                log.v { "Local project directory is missing" }
                return@withContext null
            }
            accessible(input.toRealPath())
        } catch (error: IOException) {
            log.w(error.safeWorkspaceFailure()) { "Directory validation failed" }
            null
        } catch (error: InvalidPathException) {
            log.w(error.safeWorkspaceFailure()) { "Directory validation failed" }
            null
        } catch (error: SecurityException) {
            log.w(error.safeWorkspaceFailure()) { "Directory validation failed" }
            null
        }
    }

    private fun accessible(path: Path): WorkspaceDirectory? =
        if (Files.isDirectory(path) && Files.isReadable(path) && Files.isExecutable(path)) {
            WorkspaceDirectory(path.toString(), path.fileName?.toString() ?: "Project")
        } else {
            null
        }
}
