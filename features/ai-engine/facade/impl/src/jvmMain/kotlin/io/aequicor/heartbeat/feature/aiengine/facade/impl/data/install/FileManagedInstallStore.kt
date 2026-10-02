package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StagedInstall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ManagedInstallStore] in [root]: `<engine>/versions/<version>-<sha8>` holds activated copies, `active.json` names
 * the active one (written to a temporary file and moved into place), `staging` and `downloads` hold work in
 * progress, and `trash` holds copies being removed. Operations of one engine are serialized in this process and
 * across Heartbeat processes by a file lock. A copy still used by a running process (Windows) stays in `trash`
 * until a later sweep removes it. Paths and URLs are never logged.
 */
class FileManagedInstallStore(
    private val root: Path,
    private val downloader: ReleaseDownloader,
    private val extractor: ArchiveExtractor,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) : ManagedInstallStore {
    private val log = Log.tag("ManagedInstallStorage")
    private val installs = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())
    private val guard = Mutex()
    private val locks = mutableMapOf<EngineId, Mutex>()

    override val state: StateFlow<Map<EngineId, ManagedInstall>> = installs.asStateFlow()

    override suspend fun refresh() {
        val found = withContext(io) {
            if (!Files.isDirectory(root)) return@withContext emptyMap()
            val engines = Files.list(root).use { children -> children.filter(Files::isDirectory).toList() }
            engines.mapNotNull { directory ->
                val engine = directory.fileName.toString().takeIf { EngineName.matches(it) }?.let(::EngineId)
                    ?: return@mapNotNull null
                sweep(directory)
                readActive(directory)?.let { engine to it }
            }.toMap()
        }
        installs.value = found
        log.i { "managed installs read count=${found.size}" }
    }

    override suspend fun stage(
        engine: EngineId,
        plan: InstallPlan,
        progress: suspend (InstallStep) -> Unit,
    ): StagedInstall = locked(engine) { directory ->
        val token = Uuid.random().toHexString().take(TOKEN_LENGTH)
        val download = directory.resolve(DOWNLOADS).resolve("$token.part")
        val staging = directory.resolve(STAGING).resolve(token)
        var isStaged = false
        try {
            withContext(io) {
                storage { Files.createDirectories(download.parent) }
                storage { Files.createDirectories(staging) }
            }
            downloader.download(plan, download) { bytes, total -> progress(InstallStep.Downloading(bytes, total)) }
            progress(InstallStep.Verified)
            progress(InstallStep.Unpacking)
            val executable = withContext(io) { unpack(plan, download, staging) }
            log.i { "release staged engine=${engine.value} version=${plan.version}" }
            isStaged = true
            StagedInstall(engine, ManagedInstall(plan.version, executable.toString(), clock.now()), token, plan.sha256)
        } finally {
            // Blocking file removals: quick, and they must run even when the caller was cancelled.
            deleteQuietly(download)
            if (!isStaged) deleteTree(staging)
        }
    }

    override suspend fun activate(staged: StagedInstall): ManagedInstall = locked(staged.engine) { directory ->
        val installed = withContext(io) { switchTo(directory, staged) }
        installs.update { it + (staged.engine to installed) }
        log.i { "managed copy activated engine=${staged.engine.value} version=${installed.version}" }
        installed
    }

    override suspend fun discard(staged: StagedInstall) {
        locked(staged.engine) { directory ->
            withContext(io) { deleteTree(directory.resolve(STAGING).resolve(staged.token)) }
            log.i { "staged copy discarded engine=${staged.engine.value}" }
        }
    }

    override suspend fun uninstall(engine: EngineId) {
        locked(engine) { directory ->
            val active = withContext(io) { readRecord(directory) }
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
        if (previous != null && previous.directory != name) {
            moveToTrash(directory, directory.resolve(VERSIONS).resolve(previous.directory))
        }
        sweep(directory)
        return ManagedInstall(staged.candidate.version, target.resolve(relative).toString(), installedAt)
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
                log.w(e) { "managed copy is in use attempt=${attempt + 1}" }
                delay(MOVE_RETRY_MILLIS)
            }
        }
        throw installFailure(InstallFailureReason.FilesInUse)
    }

    /** Removes interrupted downloads, staged copies and trashed copies; what is still in use stays for later. */
    private fun sweep(directory: Path) {
        listOf(TRASH, STAGING, DOWNLOADS).map(directory::resolve).filter(Files::isDirectory).forEach { folder ->
            Files.list(folder).use { children -> children.toList() }.forEach(::deleteTree)
        }
    }

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
        if (!Files.isRegularFile(file)) return null
        return try {
            RecordJson.decodeFromString(ActiveRecord.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            log.w(e) { "active managed copy record is malformed; ignored" }
            null
        } catch (e: IOException) {
            log.w(e) { "active managed copy record could not be read; ignored" }
            null
        }
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
        return mutex.withLock {
            val directory = root.resolve(engine.value)
            val lock = withContext(io) { acquire(directory) }
            try {
                block(directory)
            } finally {
                lock.channel().use { lock.release() }
            }
        }
    }

    /** The cross-process lock of [directory]; another Heartbeat process holding it makes the copy in use. */
    private fun acquire(directory: Path): FileLock {
        storage { Files.createDirectories(directory) }
        val channel = storage {
            FileChannel.open(directory.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            log.w(e) { "managed copies are locked in this process" }
            null
        }
        if (lock == null) {
            channel.close()
            log.w { "managed copies are being changed by another Heartbeat process" }
            throw installFailure(InstallFailureReason.FilesInUse)
        }
        return lock
    }

    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        log.w(e) { "managed copy files could not be written" }
        throw installFailure(InstallFailureReason.Storage, e)
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log.w(e) { "temporary release file could not be removed" }
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
        const val LOCK = ".lock"
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
