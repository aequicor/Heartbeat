package io.aequicor.heartbeat.core.network.impl.release

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Keeps verified files outside the application bundle so upgrades can reuse them without network access. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DiskVerifiedArtifactCache(
    private val root: Path,
    private val downloader: VerifiedReleaseDownloader,
    private val dispatchers: DispatcherProvider,
) : VerifiedArtifactCache {
    @Inject
    constructor(storageRoot: StorageRoot, downloader: VerifiedReleaseDownloader, dispatchers: DispatcherProvider) :
        this(Path.of(storageRoot.path()).resolve("cache/artifacts"), downloader, dispatchers)

    private val log = Log.tag("ArtifactCache")
    private val locks = ConcurrentHashMap<String, Mutex>()

    // Raw filesystem exceptions contain private paths; only their type crosses the release boundary.
    @Suppress("SwallowedException")
    override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path =
        withContext(dispatchers.io) {
            try {
                locks.computeIfAbsent(artifact.download.sha256) { Mutex() }.withLock {
                    prepareLocked(artifact, progress)
                }
            } catch (error: IOException) {
                throw ReleaseException(ReleaseFailure.Storage, origin = error.javaClass.simpleName)
            } catch (error: OverlappingFileLockException) {
                throw ReleaseException(ReleaseFailure.Storage, origin = error.javaClass.simpleName)
            }
        }

    private suspend fun prepareLocked(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path {
        Files.createDirectories(root)
        requireDirectory(root)
        val directory = root.resolve(artifact.download.sha256)
        Files.createDirectories(directory)
        requireDirectory(directory)
        FileChannel.open(directory.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val guard = channel.tryLock()
                if (guard != null) {
                    guard.use { return verifiedFile(directory, artifact, progress) }
                }
                delay(LOCK_POLL_MILLIS)
            }
        }
    }

    private suspend fun verifiedFile(
        directory: Path,
        artifact: ReleaseArtifact,
        progress: suspend (Long, Long?) -> Unit,
    ): Path {
        val target = directory.resolve(artifact.fileName)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            requireRegularFile(target)
            if (matches(target, artifact)) {
                log.v { "Reusing verified component file" }
                return target
            }
            log.w(ReleaseException(ReleaseFailure.ChecksumMismatch)) { "Replacing corrupt component cache file" }
            Files.delete(target)
        }
        val temporary = directory.resolve(".part-${UUID.randomUUID()}")
        try {
            downloader.download(artifact.download, temporary, progress)
            // Verify here too: the cache contract does not depend on a particular downloader implementation.
            requireRegularFile(temporary)
            if (!matches(temporary, artifact)) throw ReleaseException(ReleaseFailure.ChecksumMismatch)
            currentCoroutineContext().ensureActive()
            Files.move(temporary, target, ATOMIC_MOVE)
            log.d { "Pinned component file is ready" }
            return target
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private suspend fun matches(file: Path, artifact: ReleaseArtifact): Boolean {
        val size = Files.size(file)
        val request = artifact.download
        if (size > request.maxBytes || request.size?.let { it != size } == true) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest()) == request.sha256
    }

    private fun requireDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw ReleaseException(ReleaseFailure.Storage)
    }

    private fun requireRegularFile(path: Path) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw ReleaseException(ReleaseFailure.Storage)
    }
}

private const val LOCK_POLL_MILLIS = 50L
