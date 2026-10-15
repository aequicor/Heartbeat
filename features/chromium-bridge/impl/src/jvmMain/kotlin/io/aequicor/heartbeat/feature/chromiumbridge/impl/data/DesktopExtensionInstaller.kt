package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExtensionPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Explicit first-use installation; bridge startup may inspect the cache but never contacts the network. */
@SingleIn(AppScope::class)
internal class DesktopExtensionInstaller(
    private val root: Path,
    private val cache: VerifiedArtifactCache,
    private val dispatchers: DispatcherProvider,
    private val metadata: () -> String = ::extensionMetadata,
) {
    @Inject
    constructor(storage: StorageRoot, cache: VerifiedArtifactCache, dispatchers: DispatcherProvider) : this(
        Path.of(storage.path()).resolve("cache/chromium-extension"),
        cache,
        dispatchers,
    )

    private val log = Log.tag("ExtensionInstaller")
    private val mutex = Mutex()

    /** Local verification only, including content hashes; missing or invalid files remain unavailable. */
    suspend fun locate(): Path? = withContext(dispatchers.io) {
        try {
            val release = ExtensionRelease.read(metadata())
            installed(ExtensionInstallation(root.resolve(release.artifact.download.sha256), release))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Extension metadata is unavailable" }
            null
        }
    }

    /** Cancellation interrupts download/extraction; the published installation is changed only after completion. */
    suspend fun prepare(progress: suspend (ExtensionPreparation) -> Unit): Path = withContext(dispatchers.io) {
        mutex.withLock {
            val release = ExtensionRelease.read(metadata())
            val folder = root.resolve(release.artifact.download.sha256)
            Files.createDirectories(root)
            require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Extension cache must not be a link" }
            Files.createDirectories(folder)
            require(Files.isDirectory(folder, NOFOLLOW_LINKS)) { "Extension cache directory must not be a link" }
            FileChannel.open(folder.resolve(".install.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                var lock = channel.extensionLock()
                while (lock == null) {
                    delay(LOCK_POLL_MILLIS)
                    lock = channel.extensionLock()
                }
                lock.use {
                    val installation = ExtensionInstallation(folder, release)
                    installed(installation)?.let { existing -> return@withLock existing }
                    log.v { "Preparing the pinned Chromium extension" }
                    val archive = cache.prepare(release.artifact) { received, total ->
                        progress(ExtensionPreparation.Downloading(received, total))
                    }
                    progress(ExtensionPreparation.Extracting)
                    val staging = Files.createTempDirectory(folder, ".extract-")
                    try {
                        val files = ExtensionArchive().extract(archive, staging)
                        validateExtension(staging)
                        currentCoroutineContext().ensureActive()
                        installation.activate(staging, files).also { log.d { "Pinned Chromium extension is ready" } }
                    } finally {
                        removeExtensionStaging(staging)
                    }
                }
            }
        }
    }

    private suspend fun installed(installation: ExtensionInstallation): Path? = try {
        installation.installed()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Extension cache is incomplete; explicit preparation can repair it" }
        null
    }
}

private fun extensionMetadata(): String {
    val resources = checkNotNull(System.getProperty("compose.application.resources.dir")) {
        "Application resources are unavailable"
    }
    val file = Path.of(resources).resolve("chromium-extension.properties")
    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= MAX_LOCK_BYTES) {
        "Extension component lock is unavailable"
    }
    return Files.readString(file)
}

private fun FileChannel.extensionLock() = try {
    tryLock()
} catch (error: OverlappingFileLockException) {
    Log.tag("ExtensionInstaller").w(error) { "Another extension installer owns the publication lock" }
    null
}

private fun removeExtensionStaging(staging: Path) {
    if (Files.exists(staging, NOFOLLOW_LINKS)) {
        Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

private const val LOCK_POLL_MILLIS = 50L
private const val MAX_LOCK_BYTES = 16L * 1024
