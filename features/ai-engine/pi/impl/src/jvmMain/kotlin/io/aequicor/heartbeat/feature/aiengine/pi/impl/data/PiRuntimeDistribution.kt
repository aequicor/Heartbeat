package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID

/**
 * Immutable application-owned baseline Pi, installed only for actual use. Profiles share verified downloads and
 * complete extracted versions; cancelling a caller cancels its preparation, never a running Pi process. Separate
 * processes may extract into different staging directories; only complete directories are atomically published.
 */
@SingleIn(AppScope::class)
internal class PiRuntimeDistribution(
    private val root: Path,
    val release: PiRuntimeRelease?,
    private val artifacts: VerifiedArtifactCache,
    private val dispatchers: DispatcherProvider,
) {
    @Inject
    constructor(storage: StorageRoot, artifacts: VerifiedArtifactCache, dispatchers: DispatcherProvider) : this(
        Path.of(storage.path(), "cache", "pi-runtime"),
        readPiRuntimeRelease(PiRuntimeDistribution::class.java.getResourceAsStream("/pi/runtime.properties")),
        artifacts,
        dispatchers,
    )

    private val log = Log.tag("PiRuntimeDistribution")
    private val mutex = Mutex()

    @Volatile
    private var validated: Path? = null

    /** Cheap presence check for management and requirements; actual execution calls [prepare] to verify inventory. */
    fun executable(): Path? = try {
        cachedExecutable()
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "Pi cache pointer cannot be read; preparation will repair it" }
        null
    } catch (e: SecurityException) {
        log.w(e.withoutDetails()) { "Pi cache inspection was denied" }
        null
    }

    private fun cachedExecutable(): Path? {
        val pin = release ?: return null
        val directory = validated ?: activeDirectory(pin) ?: return null
        return directory.resolve(pin.executable).takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
    }

    /** Calls [progress] on the requesting context, preserving cancellation before native startup. */
    suspend fun prepare(progress: suspend (EnginePreparation) -> Unit): Path = mutex.withLock {
        val pin = checkNotNull(release) { "Pi runtime pin is unavailable" }
        progress(EnginePreparation.Installing)
        val cached = withContext(dispatchers.io) { existing(pin) }
        if (cached != null) {
            validated = cached
            return@withLock cached.resolve(pin.executable)
        }
        log.i { "Preparing pinned Pi runtime version=${pin.version}" }
        val archive = artifacts.prepare(
            pin.artifact,
        ) { bytes, total -> progress(EnginePreparation.Downloading(bytes, total)) }
        progress(EnginePreparation.Installing)
        val installed = withContext(dispatchers.io) { install(pin, archive) }
        validated = installed
        log.i { "Pinned Pi runtime ready version=${pin.version}" }
        installed.resolve(pin.executable)
    }

    private fun versionRoot(pin: PiRuntimeRelease): Path = root.resolve(pin.artifact.download.sha256)

    private fun activeDirectory(pin: PiRuntimeRelease): Path? {
        val parent = versionRoot(pin)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isDirectory(parent, NOFOLLOW_LINKS)) return null
        val pointer = parent.resolve("active")
        if (!Files.isRegularFile(pointer, NOFOLLOW_LINKS) || Files.size(pointer) > MAX_POINTER_BYTES) return null
        val name = Files.readString(pointer)
        if (!name.matches(Regex("runtime-[0-9a-f-]{36}"))) return null
        return parent.resolve(name).takeIf { Files.isDirectory(it, NOFOLLOW_LINKS) }
    }

    private suspend fun existing(pin: PiRuntimeRelease): Path? = try {
        activeDirectory(pin)?.takeIf { verify(it, pin) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.withoutDetails()) { "Pi runtime cache is incomplete; rebuilding from verified archive" }
        null
    }

    private suspend fun verify(directory: Path, pin: PiRuntimeRelease): Boolean {
        val marker = directory.resolve(MARKER)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) > MAX_MARKER_BYTES) return false
        val metadata = Json.parseToJsonElement(Files.readString(marker)).jsonObject
        require(
            metadata["sha256"]?.jsonPrimitive?.content == pin.artifact.download.sha256,
        ) { "Pi cache version differs" }
        val files = metadata.getValue("files").jsonObject
        require(files.isNotEmpty() && files.size <= MAX_FILES) { "Invalid Pi cache inventory" }
        val actual = Files.walk(directory).use { paths -> paths.filter { it != directory }.toList() }
        require(actual.none(Files::isSymbolicLink)) { "Pi cache contains a link" }
        require(
            actual.count { !Files.isDirectory(it, NOFOLLOW_LINKS) } == files.size + 1,
        ) { "Pi cache inventory differs" }
        for ((name, digest) in files) {
            currentCoroutineContext().ensureActive()
            val file = piRuntimeTarget(directory, name)
            require(
                Files.isRegularFile(file, NOFOLLOW_LINKS) && piRuntimeDigest(file) == digest.jsonPrimitive.content,
            ) {
                "Pi cache file differs"
            }
        }
        return Files.isExecutable(directory.resolve(pin.executable))
    }

    private suspend fun install(pin: PiRuntimeRelease, archive: Path): Path {
        Files.createDirectories(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Pi cache root is not a directory" }
        val parent = Files.createDirectories(versionRoot(pin))
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Pi version root is not a directory" }
        val staging = Files.createTempDirectory(parent, ".install-")
        try {
            val files = PiRuntimeArchive().extract(archive, staging, pin.isZip)
            require(MARKER !in files && pin.executable in files) { "Pi archive misses its executable" }
            if (!pin.isZip) {
                check(staging.resolve(pin.executable).toFile().setExecutable(true, false)) { "Pi cannot execute" }
            }
            val marker = buildJsonObject {
                put("sha256", pin.artifact.download.sha256)
                put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
            }
            Files.writeString(staging.resolve(MARKER), marker.toString(), CREATE_NEW)
            currentCoroutineContext().ensureActive()
            val directory = parent.resolve("runtime-${UUID.randomUUID()}")
            Files.move(staging, directory, ATOMIC_MOVE)
            publish(parent, directory.fileName.toString())
            return directory
        } finally {
            removeStaging(staging)
        }
    }

    private fun publish(parent: Path, name: String) {
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            "Pi cache destination changed"
        }
        val temporary = parent.resolve(".active-${UUID.randomUUID()}")
        try {
            Files.writeString(temporary, name, CREATE_NEW)
            Files.move(temporary, parent.resolve("active"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun removeStaging(directory: Path) {
        try {
            if (Files.exists(directory, NOFOLLOW_LINKS)) {
                Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "Pi staging cleanup failed" }
        } catch (e: UncheckedIOException) {
            log.w(e.withoutDetails()) { "Pi staging traversal failed" }
        } catch (e: SecurityException) {
            log.w(e.withoutDetails()) { "Pi staging cleanup was denied" }
        }
    }
}

private const val MARKER = ".heartbeat-runtime.json"
private const val MAX_POINTER_BYTES = 128
private const val MAX_MARKER_BYTES = 2L * 1024 * 1024
private const val MAX_FILES = 10_000
