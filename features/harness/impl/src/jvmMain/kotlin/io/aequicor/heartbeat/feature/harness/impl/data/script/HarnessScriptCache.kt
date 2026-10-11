package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.jar.JarInputStream

/**
 * Called under the host cache mutex, except for the per-compilation files of [newOutput] and [discardOutput]. Cache
 * metadata contains diagnostics, never raw source.
 */
internal class HarnessScriptCache(private val directory: Path) {
    fun itemKey(request: HarnessCompilationRequest): String =
        Json.encodeToString(listOf(request.harness.value, request.item.value)).encodeUtf8().sha256().hex()

    fun load(itemKey: String, key: String, kind: HarnessCodeKind): CachedHarnessCode? {
        val jar = directory.resolve("$itemKey.jar")
        val metadata = directory.resolve("$itemKey.json")
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(metadata)) return null
        check(Files.size(metadata) <= MAX_METADATA_BYTES)
        val header = Json.decodeFromString<HarnessCacheHeader>(Files.readString(metadata))
        val isMatching = header.key == key && header.kind == kind && header.version == CACHE_VERSION
        return if (!isMatching || header.jarSha != jar.fileDigest()) {
            null
        } else {
            val warnings = header.warnings.take(MAX_HARNESS_DIAGNOSTICS).map {
                it.copy(message = it.message.take(MAX_HARNESS_DIAGNOSTIC_CHARS))
            }
            CachedHarnessCode(readArtifact(jar, kind), warnings)
        }
    }

    /** A new temporary file for a compiler's jar; [store] moves it into the cache, [discardOutput] removes it. */
    fun newOutput(): Path {
        Files.createDirectories(directory)
        return Files.createTempFile(directory, TEMPORARY_PREFIX, ".jar")
    }

    /** Moves the compiled [jar] (from [newOutput]) into the cache with its metadata. */
    fun store(
        itemKey: String,
        key: String,
        kind: HarnessCodeKind,
        jar: Path,
        warnings: List<HarnessCodeDiagnostic>,
    ): CachedHarnessCode {
        val metadata = Files.createTempFile(directory, TEMPORARY_PREFIX, ".json")
        try {
            val header = HarnessCacheHeader(key, kind, jar.fileDigest(), warnings)
            Files.writeString(metadata, Json.encodeToString(header))
            val artifact = readArtifact(jar, kind)
            var isTransferred = false
            try {
                Files.move(jar, directory.resolve("$itemKey.jar"), ATOMIC_MOVE, REPLACE_EXISTING)
                Files.move(metadata, directory.resolve("$itemKey.json"), ATOMIC_MOVE, REPLACE_EXISTING)
                isTransferred = true
                return CachedHarnessCode(artifact, warnings)
            } finally {
                if (!isTransferred) artifact.close()
            }
        } finally {
            Files.deleteIfExists(metadata)
        }
    }

    /** Removes an [output] that was not stored; one that cannot be removed is purged by the next host. */
    fun discardOutput(output: Path) {
        try {
            Files.deleteIfExists(output)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.safeHarnessFailure()) { "compiler output remains in the cache until the next start" }
        }
    }

    /**
     * Temporary outputs survive only a crash between their write and the atomic move; none is live at startup. An
     * output that cannot be removed is logged, and the purge goes on with the next one.
     */
    fun purgeTemporary() {
        if (!Files.isDirectory(directory)) return
        Files.newDirectoryStream(directory, "$TEMPORARY_PREFIX*").use { entries ->
            entries.forEach { entry ->
                try {
                    Files.deleteIfExists(entry)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(error.safeHarnessFailure()) { "stale compiler output remains in the cache" }
                }
            }
        }
    }

    /**
     * Deletes cached jars and metadata whose item key is not in [retained]; temporary outputs and foreign files are
     * left alone. A file that cannot be removed is logged and the sweep goes on. Returns the number removed.
     */
    fun retainOnly(retained: Set<String>): Int {
        if (!Files.isDirectory(directory)) return 0
        return Files.newDirectoryStream(directory, "*.{jar,json}").use { entries ->
            entries.count { entry ->
                val key = entry.fileName.toString().substringBeforeLast('.')
                CACHE_KEY.matches(key) && key !in retained && deleteOrphan(entry)
            }
        }
    }

    private fun deleteOrphan(entry: Path): Boolean = try {
        Files.deleteIfExists(entry)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.safeHarnessFailure()) { "orphaned harness cache entry remains until the next load" }
        false
    }

    fun remove(itemKey: String) {
        Files.deleteIfExists(directory.resolve("$itemKey.jar"))
        Files.deleteIfExists(directory.resolve("$itemKey.json"))
    }

    private fun readArtifact(jar: Path, kind: HarnessCodeKind): HarnessMemoryCode =
        JarInputStream(Files.newInputStream(jar)).use { input ->
            val className = checkNotNull(input.manifest?.mainAttributes?.getValue("Main-Class"))
            val resources = mutableMapOf<String, ByteArray>()
            var bytes = 0L
            var entry = input.nextJarEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val value = input.readNBytes(MAX_ARTIFACT_BYTES + 1)
                    bytes += value.size
                    check(bytes <= MAX_ARTIFACT_BYTES && resources.size < MAX_ARTIFACT_ENTRIES)
                    resources[entry.name] = value
                }
                input.closeEntry()
                entry = input.nextJarEntry
            }
            HarnessMemoryCode.create(kind, className, resources)
        }
}

internal data class CachedHarnessCode(val code: HarnessMemoryCode, val warnings: List<HarnessCodeDiagnostic>) {
    override fun toString(): String = "CachedHarnessCode(***)"
}

@Serializable
private data class HarnessCacheHeader(
    val key: String,
    val kind: HarnessCodeKind,
    val jarSha: String,
    val warnings: List<HarnessCodeDiagnostic>,
    val version: Int = CACHE_VERSION,
) {
    override fun toString(): String = "HarnessCacheHeader(***)"
}

internal fun Path.fileDigest(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count != -1) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    return digest.digest().toByteString().hex()
}

private const val CACHE_VERSION = 1
private val CACHE_KEY = Regex("[0-9a-f]{64}")
private const val TEMPORARY_PREFIX = "compiler-"
private val log = Log.tag("HarnessScriptCache")
private const val MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
private const val MAX_ARTIFACT_ENTRIES = 4096

private const val MAX_METADATA_BYTES = 128 * 1024L
