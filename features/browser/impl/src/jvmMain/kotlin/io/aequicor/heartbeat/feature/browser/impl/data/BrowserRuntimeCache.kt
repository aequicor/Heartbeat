package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID

/** Publishes immutable installations through a small atomic pointer; older processes keep their own loaded files. */
internal class BrowserRuntimeCache(private val root: Path, private val lock: BrowserRuntimeLock) {
    /** Returns only a complete cache whose inventory, metadata and every native file still match their digests. */
    suspend fun installed(): Path? {
        val directory = activeDirectory() ?: return null
        val files = readInventory(directory)
        verifyInventory(directory, files)
        validateBrowserRuntime(directory, lock.releaseTag, lock.platform)
        return directory
    }

    private fun activeDirectory(): Path? {
        val pointer = root.resolve("active")
        if (!Files.isRegularFile(pointer, NOFOLLOW_LINKS) || Files.size(pointer) > MAX_POINTER_BYTES) return null
        val name = Files.readString(pointer)
        require(name.matches(Regex("runtime-[0-9a-f-]{36}"))) { "Invalid browser cache pointer" }
        val directory = root.resolve(name)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Browser cache directory is missing" }
        return directory
    }

    private fun readInventory(directory: Path): JsonObject {
        val marker = directory.resolve(MARKER)
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS)) { "Browser cache marker is missing" }
        require(Files.size(marker) <= MAX_MARKER_BYTES) { "Browser cache marker is too large" }
        val metadata = Json.parseToJsonElement(Files.readString(marker)).jsonObject
        require(metadata["sha256"]?.jsonPrimitive?.content == lock.sha256) { "Browser cache version differs" }
        val files = metadata.getValue("files").jsonObject
        require(files.isNotEmpty() && files.size <= MAX_CACHED_FILES) { "Invalid browser cache inventory" }
        return files
    }

    private suspend fun verifyInventory(directory: Path, files: JsonObject) {
        val actual = Files.walk(directory).use { paths -> paths.filter { it != directory }.toList() }
        require(actual.none { Files.isSymbolicLink(it) }) { "Browser cache contains a link" }
        val regular = actual.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
        require(regular.size == files.size + 1) { "Browser cache inventory differs" }
        for ((relative, expected) in files) {
            currentCoroutineContext().ensureActive()
            val file = browserRuntimeTarget(directory, relative)
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Browser cache file is missing" }
            require(browserRuntimeDigest(file) == expected.jsonPrimitive.content) { "Browser cache file differs" }
        }
    }

    /** Writes the complete marker before exposing the directory; the old pointer remains valid on any failure. */
    fun activate(staging: Path, files: Map<String, String>): Path {
        require(MARKER !in files) { "Browser archive overlaps its completion marker" }
        val marker = buildJsonObject {
            put("sha256", lock.sha256)
            put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
        }
        Files.writeString(staging.resolve(MARKER), marker.toString(), CREATE_NEW)
        val name = "runtime-${UUID.randomUUID()}"
        val directory = root.resolve(name)
        Files.move(staging, directory, ATOMIC_MOVE)
        val pointer = root.resolve(".active-${UUID.randomUUID()}")
        try {
            Files.writeString(pointer, name, CREATE_NEW)
            Files.move(pointer, root.resolve("active"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pointer)
        }
        return directory
    }
}

private const val MARKER = ".heartbeat-runtime.json"
private const val MAX_MARKER_BYTES = 2L * 1024 * 1024

private const val MAX_POINTER_BYTES = 128
private const val MAX_CACHED_FILES = 10_000
