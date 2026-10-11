package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.feature.attachments.api.attachmentMediaTypeFor
import kotlinx.coroutines.ensureActive
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext

private val IGNORED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "target", "dist", "out",
)
private const val ENTRY_LIMIT = 20_000
private const val DEPTH_LIMIT = 16

/** Bounds all visited entries, including empty directories, and only returns regular files without following links. */
internal fun walkProjectFiles(
    root: Path,
    context: CoroutineContext,
    entryLimit: Int = ENTRY_LIMIT,
    depthLimit: Int = DEPTH_LIMIT,
): List<ProjectFile> {
    context.ensureActive()
    val visitor = ProjectFileVisitor(root, context, entryLimit)
    Files.walkFileTree(root, emptySet(), depthLimit, visitor)
    return visitor.files
}

private class ProjectFileVisitor(
    private val root: Path,
    private val context: CoroutineContext,
    private val entryLimit: Int,
) : SimpleFileVisitor<Path>() {
    val files = mutableListOf<ProjectFile>()
    private var visited = 0

    private fun visit(): Boolean {
        context.ensureActive()
        if (visited >= entryLimit) return false
        visited++
        return true
    }

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!visit()) return FileVisitResult.TERMINATE
        return if (dir != root && dir.fileName?.toString() in IGNORED_DIRECTORIES) {
            FileVisitResult.SKIP_SUBTREE
        } else {
            FileVisitResult.CONTINUE
        }
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!visit()) return FileVisitResult.TERMINATE
        // walkFileTree also reports symlinks and directories at its depth limit through visitFile.
        if (attrs.isRegularFile) {
            val relative = root.relativize(file).toString().replace('\\', '/')
            files += ProjectFile(relative, file.toString(), attrs.size(), attachmentMediaTypeFor(relative))
        }
        return FileVisitResult.CONTINUE
    }
}
