package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StagedInstall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileLock
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ManagedInstallStore] in [root]: `<engine>/versions/<version>-<sha8>` holds activated copies, `active.json` names
 * the active one (written to a temporary file and moved into place), `staging` and `downloads` hold work in
 * progress, and `trash` holds copies being removed. Operations of one engine are serialized in this process and
 * across Heartbeat processes by a file lock; a refresh sweeps leftovers only of engines it can lock. A replaced copy
 * that a running process still uses (Windows) stays where it is and is swept once nothing uses it. Paths and URLs
 * are never logged, and neither are file system messages, which contain them.
 */
internal class FileManagedInstallStore(
    private val root: Path,
    private val downloader: ReleaseDownloader,
    private val extractor: ArchiveExtractor,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val fileLocks: ManagedFileLocks,
) : ManagedInstallStore {
    private val log = Log.tag("ManagedInstallStorage")
    private val installs = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())
    private val guard = Mutex()
    private val locks = mutableMapOf<EngineId, Mutex>()

    override val state: StateFlow<Map<EngineId, ManagedInstall>> = installs.asStateFlow()

    override suspend fun refresh() {
        val engines = withContext(io) {
            if (!Files.isDirectory(root)) return@withContext emptyList()
            storage { Files.list(root).use { children -> children.filter(Files::isDirectory).toList() } }
                .mapNotNull { directory -> directory.fileName.toString().takeIf { EngineName.matches(it) } }
                .map(::EngineId)
        }
        var skipped = 0
        engines.forEach { engine ->
            // An engine changed right now (here or by another process) keeps its state and its files.
            val isRead = tryLocked(engine) { directory ->
                val active = withContext(io) {
                    sweep(directory)
                    readActive(directory)
                }
                installs.update { if (active == null) it - engine else it + (engine to active) }
            }
            if (!isRead) skipped++
        }
        installs.update { current -> current.filterKeys { it in engines } }
        log.i { "managed installs read count=${installs.value.size} skipped=$skipped" }
    }

    override suspend fun stage(
        engine: EngineId,
        plan: InstallPlan,
        progress: suspend (InstallStep) -> Unit,
    ): StagedInstall = locked(engine) { directory ->
        val token = Uuid.random().toHexString().take(TOKEN_LENGTH)
        val download = directory.resolve(DOWNLOADS).resolve("$token.part")
        val staging = directory.resolve(STAGING).resolve(token)
        var lease: FileLock? = null
        var isHandedOver = false
        try {
            withContext(io) {
                storage { Files.createDirectories(download.parent) }
                storage { Files.createDirectories(staging) }
                lease = fileLocks.acquire(staging, ManagedLock.Candidate)
            }
            downloader.download(plan, download) { bytes, total -> progress(InstallStep.Downloading(bytes, total)) }
            progress(InstallStep.Verified)
            progress(InstallStep.Unpacking)
            val executable = withContext(io) { unpack(plan, download, staging) }
            val candidate = ManagedInstall(plan.version, executable.toString(), clock.now())
            withContext(NonCancellable) { withContext(io) { deleteQuietly(download) } }
            currentCoroutineContext().ensureActive()
            log.i { "release staged engine=${engine.value} version=${plan.version}" }
            val staged = StagedInstall(engine, candidate, token, plan.sha256) {
                fileLocks.release(lease)
                deleteQuietly(staging.resolve(ManagedLock.Candidate.fileName))
            }
            isHandedOver = true
            staged
        } finally {
            if (!isHandedOver) {
                // Own the lease until the candidate actually reaches the caller, including dispatch cancellation.
                withContext(NonCancellable) {
                    removeFailedStage(lease, download, staging)
                }
            }
        }
    }

    private suspend fun removeFailedStage(lease: FileLock?, download: Path, staging: Path) = withContext(io) {
        fileLocks.release(lease)
        deleteQuietly(download)
        deleteTree(staging)
    }

    override suspend fun activate(staged: StagedInstall): ManagedInstall = locked(staged.engine) { directory ->
        withContext(NonCancellable + io) {
            staged.release()
            val installed = switchTo(directory, staged)
            installs.update { it + (staged.engine to installed) }
            log.i { "managed copy activated engine=${staged.engine.value} version=${installed.version}" }
            installed
        }
    }

    override suspend fun discard(staged: StagedInstall) {
        try {
            locked(staged.engine) { directory ->
                withContext(NonCancellable + io) {
                    staged.release()
                    deleteTree(directory.resolve(STAGING).resolve(staged.token))
                }
                log.i { "staged copy discarded engine=${staged.engine.value}" }
            }
        } finally {
            // A competing engine operation may refuse discard; the abandoned candidate must remain sweepable.
            withContext(NonCancellable) { releaseStaged(staged) }
        }
    }

    private suspend fun releaseStaged(staged: StagedInstall) = withContext(io) { staged.release() }

    override suspend fun uninstall(engine: EngineId) {
        locked(engine) { directory ->
            withContext(NonCancellable + io) {
                val active = readRecord(directory)
                if (active != null) {
                    moveToTrash(directory, directory.resolve(VERSIONS).resolve(active.directory))
                    withContext(io) {
                        storage { Files.deleteIfExists(directory.resolve(ACTIVE)) }
                        sweep(directory)
                    }
                }
                installs.update { it - engine }
                log.i { "managed copy removed engine=${engine.value} wasInstalled=${active != null}" }
            }
        }
    }

    private fun unpack(plan: InstallPlan, download: Path, staging: Path): Path {
        extractor.extract(download, plan.archive, staging)
        val executable = safeTarget(staging, plan.executable)
        if (!Files.isRegularFile(executable)) {
            log.w { "release does not contain its executable version=${plan.version}" }
            throw installFailure(InstallFailureReason.ExecutableMissing)
        }
        storage { markExecutable(executable) }
        return executable
    }

    private suspend fun switchTo(directory: Path, staged: StagedInstall): ManagedInstall {
        val sha256 = staged.sha256
        val staging = directory.resolve(STAGING).resolve(staged.token)
        val name = "${staged.candidate.version.filter { it.isLetterOrDigit() || it in VersionSymbols }}-${sha256.take(
            8,
        )}"
        val target = directory.resolve(VERSIONS).resolve(name)
        val previous = readRecord(directory)
        val relative = staging.relativize(Path.of(staged.candidate.executable)).joinToString("/")
        if (Files.exists(target)) moveToTrash(directory, target)
        storage { Files.createDirectories(target.parent) }
        storage { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
        val installedAt = clock.now()
        writeRecord(directory, ActiveRecord(staged.candidate.version, name, relative, sha256, installedAt))
        // The new copy is active from here on; the previous one is removed now or, while still in use, later.
        if (previous != null && previous.directory != name) {
            putAside(directory, directory.resolve(VERSIONS).resolve(previous.directory))
        }
        return ManagedInstall(staged.candidate.version, target.resolve(relative).toString(), installedAt)
    }

    /** Moves a copy that is no longer active to the trash in one attempt; one still in use is swept later. */
    private fun putAside(directory: Path, path: Path) {
        if (!Files.exists(path)) return
        try {
            Files.createDirectories(directory.resolve(TRASH))
            val trashed = directory.resolve(TRASH).resolve(Uuid.random().toHexString())
            Files.move(path, trashed, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "replaced managed copy is still in use; it is removed later" }
        }
    }

    /** Moves [path] aside; a copy a running process still uses (Windows) is retried, then reported as in use. */
    private suspend fun moveToTrash(directory: Path, path: Path) = withContext(io) {
        if (!Files.exists(path)) return@withContext
        val trash = directory.resolve(TRASH)
        repeat(MOVE_ATTEMPTS) { attempt ->
            try {
                Files.createDirectories(trash)
                Files.move(path, trash.resolve(Uuid.random().toHexString()), StandardCopyOption.ATOMIC_MOVE)
                return@withContext
            } catch (e: FileSystemException) {
                log.w(e.withoutDetails()) { "managed copy is in use attempt=${attempt + 1}" }
                delay(MOVE_RETRY_MILLIS)
            }
        }
        throw installFailure(InstallFailureReason.FilesInUse)
    }

    /**
     * Removes interrupted downloads, staged copies, trashed copies and copies no record names any more (a replaced
     * copy that was in use when it was replaced). Each copy is first moved to the trash, which fails while a process
     * still uses it (Windows), so a running copy is never deleted half-way; it stays for a later sweep.
     */
    private fun sweep(directory: Path) {
        val active = readRecord(directory)?.directory
        directory.resolve(VERSIONS).takeIf(Files::isDirectory)?.let { versions ->
            children(versions).filter { it.fileName.toString() != active }.forEach { putAside(directory, it) }
        }
        listOf(TRASH, DOWNLOADS).map(directory::resolve).filter(Files::isDirectory).forEach { folder ->
            children(folder).forEach(::deleteTree)
        }
        directory.resolve(STAGING).takeIf(Files::isDirectory)?.let { folder ->
            children(folder).filter(::isAbandoned).forEach(::deleteTree)
        }
    }

    /** A candidate stays leased between download and activation, including while another profile refreshes. */
    private fun isAbandoned(staging: Path): Boolean = fileLocks.isAbandoned(staging)

    private fun children(folder: Path): List<Path> = storage { Files.list(folder).use { it.toList() } }

    private fun readActive(directory: Path): ManagedInstall? {
        val record = readRecord(directory) ?: return null
        val version = directory.resolve(VERSIONS).resolve(record.directory).normalize()
        val executable = version.resolve(record.executable).normalize()
        val isInside = version.parent == directory.resolve(VERSIONS).normalize() && executable.startsWith(version)
        if (!isInside || !Files.isRegularFile(executable)) {
            log.w { "active managed copy is missing or outside its folder; ignored" }
            return null
        }
        return ManagedInstall(record.version, executable.toString(), record.installedAt)
    }

    private fun readRecord(directory: Path): ActiveRecord? {
        val file = directory.resolve(ACTIVE)
        if (!Files.exists(file)) return null
        return try {
            RecordJson.decodeFromString(ActiveRecord.serializer(), Files.readString(file)).also { record ->
                validateRecord(directory, record)
            }
        } catch (e: SerializationException) {
            throw recordFailure(e)
        } catch (e: IOException) {
            throw recordFailure(e)
        }
    }

    private fun validateRecord(directory: Path, record: ActiveRecord) {
        val version = directory.resolve(VERSIONS).resolve(record.directory).normalize()
        val isName = record.directory !in setOf("", ".", "..") &&
            record.directory.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
        if (!isName || version.parent != directory.resolve(VERSIONS).normalize() || Files.isSymbolicLink(version)) {
            log.w { "managed copy record points outside its version folder" }
            throw installFailure(InstallFailureReason.Storage)
        }
        safeTarget(version, record.executable)
    }

    private fun recordFailure(error: Exception): ManagementException {
        log.w(error.withoutDetails()) { "active managed copy record could not be read; files are kept" }
        return installFailure(InstallFailureReason.Storage, error.withoutDetails())
    }

    private fun writeRecord(directory: Path, record: ActiveRecord) {
        val temporary = directory.resolve("$ACTIVE.${Uuid.random().toHexString()}.tmp")
        storage {
            Files.writeString(temporary, RecordJson.encodeToString(ActiveRecord.serializer(), record))
            Files.move(
                temporary,
                directory.resolve(ACTIVE),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private suspend fun <T> locked(engine: EngineId, block: suspend (Path) -> T): T {
        require(EngineName.matches(engine.value)) { "Engine id cannot name a folder" }
        val mutex = guard.withLock { locks.getOrPut(engine) { Mutex() } }
        return mutex.withLock { holding(engine, block) }
    }

    /** Runs [block] when nobody changes [engine] right now, here or in another process; false otherwise. */
    private suspend fun tryLocked(engine: EngineId, block: suspend (Path) -> Unit): Boolean {
        val mutex = guard.withLock { locks.getOrPut(engine) { Mutex() } }
        if (!mutex.tryLock()) return false
        return try {
            holding(engine, block)
            true
        } catch (e: ManagementException) {
            if (e.failure != ManagementFailure.Install(InstallFailureReason.FilesInUse)) throw e
            log.w(e) { "managed copies are being changed elsewhere; left as they are engine=${engine.value}" }
            false
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> holding(engine: EngineId, block: suspend (Path) -> T): T {
        val directory = root.resolve(engine.value)
        var acquired: FileLock? = null
        return try {
            withContext(io) { fileLocks.acquire(directory, ManagedLock.Operation).also { acquired = it } }
            block(directory)
        } finally {
            fileLocks.release(acquired)
        }
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "managed copy files could not be written" }
        throw installFailure(InstallFailureReason.Storage, e.withoutDetails())
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "temporary release file could not be removed" }
        }
    }

    private fun deleteTree(path: Path) {
        if (!path.toFile().deleteRecursively()) log.w { "leftover managed files could not be removed yet" }
    }

    /** The record of the active copy; paths are relative to the engine's folder. */
    @Serializable
    private data class ActiveRecord(
        val version: String,
        val directory: String,
        val executable: String,
        val sha256: String,
        val installedAt: Instant,
    )

    private companion object {
        const val ACTIVE = "active.json"
        const val VERSIONS = "versions"
        const val STAGING = "staging"
        const val DOWNLOADS = "downloads"
        const val TRASH = "trash"
        const val TOKEN_LENGTH = 16
        const val MOVE_ATTEMPTS = 5
        const val MOVE_RETRY_MILLIS = 200L
        val EngineName = Regex("[a-z0-9][a-z0-9_.-]*")
        val VersionSymbols = setOf('.', '-', '_', '+')
        val RecordJson = Json { ignoreUnknownKeys = true }
    }
}
