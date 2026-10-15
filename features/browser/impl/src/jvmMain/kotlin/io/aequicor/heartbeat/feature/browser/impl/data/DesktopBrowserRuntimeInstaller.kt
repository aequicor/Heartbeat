package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimeFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cef.CefApp
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipFile

/**
 * Prepares the API's pinned native runtime on first use, with shared progress and verified offline reuse.
 * Only the app-owned initializer calls this installer; a surface stopping its await never cancels the download.
 * Failed or cancelled attempts leave the active installation intact and may be retried explicitly.
 */
@SingleIn(AppScope::class)
internal class DesktopBrowserRuntimeInstaller(
    private val root: Path,
    private val downloader: VerifiedReleaseDownloader,
    private val dispatchers: DispatcherProvider,
    private val metadata: () -> String = ::bundledBrowserLock,
    private val bundledArchive: (String) -> InputStream? = { CefApp::class.java.getResourceAsStream("/$it") },
) {
    @Inject
    constructor(
        storageRoot: StorageRoot,
        downloader: VerifiedReleaseDownloader,
        dispatchers: DispatcherProvider,
    ) : this(
        Path.of(storageRoot.path()).resolve("cache/browser/jcef"),
        downloader,
        dispatchers,
    )

    private val log = Log.tag("BrowserRuntimeInstaller")
    private val mutex = Mutex()
    private val mutablePreparation = MutableStateFlow<BrowserRuntimePreparation>(BrowserRuntimePreparation.Idle)
    val preparation = mutablePreparation.asStateFlow()
    private var prepared: Pair<BrowserRuntimeLock, Path>? = null

    /** Downloads only the selected host's pinned JAR, verifies it, then installs its exact native archive. */
    suspend fun prepare(releaseTag: String, platform: String): Path = withContext(dispatchers.io) {
        mutex.withLock {
            try {
                val lock = BrowserRuntimeLock.read(metadata(), releaseTag, platform)
                prepared?.takeIf { it.first == lock }?.second ?: install(lock).also {
                    prepared = lock to it
                    mutablePreparation.value = BrowserRuntimePreparation.Ready
                    log.d { "Pinned browser component is ready" }
                }
            } catch (error: CancellationException) {
                mutablePreparation.value = BrowserRuntimePreparation.Idle
                throw error
            } catch (error: Exception) {
                mutablePreparation.value = BrowserRuntimePreparation.Failed(error.runtimeFailure())
                log.w(error.redactedFailure()) { "Browser component preparation failed" }
                throw error
            }
        }
    }

    private suspend fun install(lock: BrowserRuntimeLock): Path {
        val directory = root.resolve(lock.version).resolve("${lock.platform}-${lock.sha256}")
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Browser cache directory must not be a link" }
        FileChannel.open(directory.resolve(".install.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.tryLock().use { guard ->
                checkNotNull(guard) { "Browser component installation is already running" }
                val cache = BrowserRuntimeCache(directory, lock)
                readCache(cache)?.let {
                    log.v { "Reusing verified browser component cache" }
                    return it
                }
                return installNew(directory, lock, cache)
            }
        }
    }

    private suspend fun readCache(cache: BrowserRuntimeCache): Path? = try {
        cache.installed()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Browser component cache is incomplete; preparing a new installation" }
        null
    }

    private suspend fun installNew(directory: Path, lock: BrowserRuntimeLock, cache: BrowserRuntimeCache): Path {
        log.v { "Preparing a new browser component installation" }
        val staging = Files.createTempDirectory(directory, ".staging-")
        try {
            val content = Files.createDirectory(staging.resolve("content"))
            val files = bundledArchive(lock.archive)?.use { bundled ->
                mutablePreparation.value = BrowserRuntimePreparation.Installing
                BrowserRuntimeArchive().extract(bundled, content)
            } ?: downloadAndExtract(staging, content, lock)
            validateBrowserRuntime(content, lock.releaseTag, lock.platform)
            return cache.activate(content, files)
        } finally {
            withContext(NonCancellable) {
                Files.walk(staging).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            }
        }
    }

    private suspend fun downloadAndExtract(
        staging: Path,
        content: Path,
        lock: BrowserRuntimeLock,
    ): Map<String, String> {
        log.v { "Downloading pinned browser component" }
        val jar = staging.resolve("native.jar")
        mutablePreparation.value = BrowserRuntimePreparation.Downloading(0, null)
        downloader.download(
            ReleaseDownload(lock.url, lock.sha256, null, GitHubDownloadHosts, spaceFactor = 6),
            jar,
        ) { bytes, total -> mutablePreparation.value = BrowserRuntimePreparation.Downloading(bytes, total) }
        mutablePreparation.value = BrowserRuntimePreparation.Installing
        return ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name == lock.archive }.toList()
            require(
                entries.size == 1 && !entries.single().isDirectory,
            ) { "Browser runtime archive is missing or duplicated" }
            zip.getInputStream(entries.single()).use { BrowserRuntimeArchive().extract(it, content) }
        }
    }
}

private fun bundledBrowserLock(): String = checkNotNull(
    DesktopBrowserRuntimeInstaller::class.java.getResourceAsStream("/browser/jcef-runtime-lock.json"),
) { "Browser runtime lock is missing" }.bufferedReader().use { it.readText() }

private fun Exception.runtimeFailure(): BrowserRuntimeFailure = when (this) {
    is ReleaseException -> when (failure) {
        ReleaseFailure.Http, ReleaseFailure.Network -> BrowserRuntimeFailure.Network

        ReleaseFailure.Storage, ReleaseFailure.NotEnoughSpace -> BrowserRuntimeFailure.Storage

        ReleaseFailure.InvalidMetadata, ReleaseFailure.UntrustedSource, ReleaseFailure.TooLarge,
        ReleaseFailure.SizeMismatch, ReleaseFailure.ChecksumMismatch,
        -> BrowserRuntimeFailure.InvalidRuntime
    }

    is IOException -> BrowserRuntimeFailure.Storage

    is IllegalArgumentException -> BrowserRuntimeFailure.InvalidRuntime

    else -> BrowserRuntimeFailure.Unavailable
}
