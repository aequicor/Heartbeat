package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.attachments.api.attachmentMediaTypeFor
import io.aequicor.heartbeat.feature.autocomplete.impl.domain.fileRank
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = Log.tag("Autocomplete/ProjectFiles")

/** Build outputs, caches and VCS metadata never reach the completion list. */
private val IGNORED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "target", "dist", "out",
)

/** The walk stops after this many files, so a runaway directory tree cannot stall typing. */
private const val ENTRY_LIMIT = 20_000

/** Deepest listed subdirectory; project sources live far above this. */
private const val DEPTH_LIMIT = 16

/** A fresh walk replaces a cached one at most this often while the user keeps typing. */
private val INDEX_TTL: Duration = 10.seconds

/**
 * Desktop index of one workspace directory: a bounded walk without symbolic links, cached per workspace for
 * a short time, with query ranking done in memory. The absolute location leaves this class only inside one
 * accepted suggestion; nothing is persisted or logged.
 */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopProjectFileIndex(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : ProjectFileIndex {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Entry>()

    override suspend fun search(workspace: WorkspaceRef?, query: String, limit: Int): List<ProjectFile> {
        val ref = workspace ?: return emptyList()
        val now = clock.now()
        val files = mutex.withLock {
            cache[ref.value]?.takeIf { now - it.fetchedAt < INDEX_TTL }?.files
        } ?: run {
            val fresh = withContext(dispatchers.io) { walk(ref) }
            mutex.withLock {
                cache[ref.value] = Entry(now, fresh)
            }
            fresh
        }
        return files.mapNotNull { file -> fileRank(query, file.relativePath).takeIf { it >= 0 }?.let { file to it } }
            .sortedWith(compareBy({ it.second }, { it.first.relativePath }))
            .take(limit)
            .map { it.first }
    }

    private suspend fun walk(ref: WorkspaceRef): List<ProjectFile> {
        val root = workspaces.resolve(ref) ?: return emptyList()
        return withContext(dispatchers.io) {
            val files = mutableListOf<ProjectFile>()
            try {
                Files.walkFileTree(
                    Path.of(root),
                    mutableSetOf(),
                    DEPTH_LIMIT,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (files.size >= ENTRY_LIMIT) return FileVisitResult.TERMINATE
                            val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                            if (dir != Path.of(root) && name in IGNORED_DIRECTORIES) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (files.size < ENTRY_LIMIT) {
                                val relative = Path.of(root).relativize(file).toString().replace('\\', '/')
                                files += ProjectFile(
                                    relativePath = relative,
                                    location = file.toString(),
                                    sizeBytes = attrs.size(),
                                    mediaType = attachmentMediaTypeFor(relative),
                                )
                            }
                            return if (files.size >=
                                ENTRY_LIMIT
                            ) {
                                FileVisitResult.TERMINATE
                            } else {
                                FileVisitResult.CONTINUE
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable tree contributes no files; typing must not fail because of the filesystem.
                log.w(e) { "workspace file walk failed" }
            }
            files
        }
    }

    private data class Entry(val fetchedAt: Instant, val files: List<ProjectFile>)
}
