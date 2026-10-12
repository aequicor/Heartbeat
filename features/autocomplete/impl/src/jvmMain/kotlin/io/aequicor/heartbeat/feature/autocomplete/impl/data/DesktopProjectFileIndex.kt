package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.autocomplete.impl.domain.fileRank
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = Log.tag("Autocomplete/ProjectFiles")

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
        return files.asSequence()
            .mapNotNull { file -> fileRank(query, file.relativePath).takeIf { it >= 0 }?.let { file to it } }
            .sortedWith(compareBy({ it.second }, { it.first.relativePath }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    private suspend fun walk(ref: WorkspaceRef): List<ProjectFile> {
        val root = workspaces.resolve(ref) ?: return emptyList()
        return withContext(dispatchers.io) {
            try {
                walkProjectFiles(Path.of(root), currentCoroutineContext())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable tree contributes no files; typing must not fail because of the filesystem.
                log.w(e) { "workspace file walk failed" }
                emptyList()
            }
        }
    }

    private data class Entry(val fetchedAt: Instant, val files: List<ProjectFile>)
}
