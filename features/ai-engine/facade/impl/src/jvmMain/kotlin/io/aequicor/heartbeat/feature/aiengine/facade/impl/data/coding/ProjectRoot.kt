package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** A model-supplied path that leaves the project or cannot be a path at all. */
internal class OutsideProjectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The directory a coding session works in. Every model-supplied path is resolved against it and must stay inside,
 * after `..` normalization and after following symbolic links of its existing part, so neither `../` nor a link
 * pointing outside reaches the rest of the machine.
 */
internal class ProjectRoot(directory: Path) {
    val path: Path = directory.toRealPath()

    init {
        require(Files.isDirectory(path)) { "Project root is not a directory" }
    }

    /** Absolute path of [raw] (relative to the root, or absolute inside it). */
    fun resolve(raw: String): Path {
        val candidate = try {
            path.resolve(raw.trim().ifEmpty { "." }).normalize()
        } catch (e: InvalidPathException) {
            throw OutsideProjectException("Invalid path: ${e.reason}", e)
        }
        val realPath = real(candidate)
        if (!candidate.startsWith(path) || !realPath.startsWith(path)) {
            throw OutsideProjectException("Path is outside the project: $raw")
        }
        rejectMetadata(candidate, realPath)
        return candidate
    }

    private fun rejectMetadata(candidate: Path, realPath: Path) {
        if (path.relativize(candidate).firstOrNull()?.toString().equals(".git", ignoreCase = true) ||
            path.relativize(realPath).firstOrNull()?.toString().equals(".git", ignoreCase = true)
        ) {
            throw OutsideProjectException("Direct Git metadata access is unavailable")
        }
    }

    /** Project-relative form of [file] with `/` separators, `.` for the root. */
    fun relative(file: Path): String = path.relativize(file).toString().replace('\\', '/').ifEmpty { "." }

    /** Real location of the longest existing prefix of [candidate] followed by its missing tail. */
    private fun real(candidate: Path): Path {
        var existing = candidate
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.parent ?: return candidate
        }
        return existing.toRealPath().resolve(existing.relativize(candidate)).normalize()
    }
}
