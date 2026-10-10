package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.native

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeCallClassifier
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** Desktop filesystem inspection shared by Pi and Claude; uncertainty always requires a user decision. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopNativeCallClassifier(private val dispatchers: DispatcherProvider) : NativeCallClassifier {
    private val log = Log.tag("NativeCallClassifier")

    override fun terminatesHost(command: String): Boolean = terminatesHostCommand(command)

    override suspend fun isWorkspaceEdit(path: String, workspace: String): Boolean = withContext(dispatchers.io) {
        val isRewritten = path.startsWith("~") || path.startsWith("@") || path.startsWith("file:", ignoreCase = true)
        if (path.isBlank() || isRewritten || UNICODE_SPACES.containsMatchIn(path)) return@withContext false
        try {
            insideWorkspace(Path.of(path), Path.of(workspace))
        } catch (e: IOException) {
            log.w(pathFailure(e)) { "Native edit target could not be resolved; asking the user" }
            false
        } catch (e: InvalidPathException) {
            log.w(pathFailure(e)) { "Native edit target is not a valid path; asking the user" }
            false
        } catch (e: SecurityException) {
            log.w(pathFailure(e)) { "Native edit target could not be inspected; asking the user" }
            false
        }
    }
}

/** Rejects lexical ambiguity before following links, then checks the real Git metadata root as well. */
private fun insideWorkspace(target: Path, workspace: Path): Boolean {
    // Lexically normalising a link/../file can change which file the operating system edits.
    if (!target.isAbsolute || target != target.normalize() || target.hasGitSegment()) return false
    val root = workspace.toRealPath()
    val real = realPath(target)
    val metadata = root.resolve(".git")
    val realMetadata = if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) metadata.toRealPath() else metadata
    return real.startsWith(root) && !real.startsWith(realMetadata) && !root.relativize(real).hasGitSegment()
}

private fun Path.hasGitSegment(): Boolean = any { it.toString().equals(".git", ignoreCase = true) }

/** Real path of the nearest existing ancestor, followed by the missing names of a file to be created. */
private fun realPath(path: Path): Path {
    var existing = path
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return path
    return existing.toRealPath().resolve(existing.relativize(path)).normalize()
}

private fun pathFailure(error: Exception): IllegalStateException =
    IllegalStateException("Path classification failed (${error::class.simpleName.orEmpty()})")

private val UNICODE_SPACES = Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]")
