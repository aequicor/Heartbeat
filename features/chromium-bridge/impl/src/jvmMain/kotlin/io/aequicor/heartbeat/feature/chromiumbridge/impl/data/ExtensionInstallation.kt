package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionIdentity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Immutable directories keep an already loaded Chromium extension intact while an installation is repaired. */
internal class ExtensionInstallation(private val root: Path, private val release: ExtensionRelease) {
    suspend fun installed(): Path? {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return null
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root.parent)) {
            "Invalid extension cache directory"
        }
        val pointer = root.resolve("active")
        if (!Files.isRegularFile(pointer, NOFOLLOW_LINKS) || Files.size(pointer) > MAX_POINTER_BYTES) return null
        val name = Files.readString(pointer)
        require(name.matches(Regex("e-[0-9a-f]{32}"))) { "Invalid extension cache pointer" }
        val folder = root.resolve(name)
        require(Files.isDirectory(folder, NOFOLLOW_LINKS)) { "Extension cache directory is missing" }
        val marker = folder.resolve(COMPLETION)
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) <= MAX_MARKER_BYTES) {
            "Invalid extension cache marker"
        }
        val metadata = Json.parseToJsonElement(Files.readString(marker)).jsonObject
        require(metadata["sha256"]?.jsonPrimitive?.content == release.artifact.download.sha256) {
            "Extension cache version differs"
        }
        val files = metadata.getValue("files").jsonObject
        require(files.isNotEmpty() && files.size <= MAX_ENTRIES) { "Invalid extension inventory" }
        val actual = Files.walk(
            folder,
        ).use { it.filter { path -> path != folder }.limit(MAX_CACHED_ENTRIES + 1L).toList() }
        require(
            actual.size <= MAX_CACHED_ENTRIES && actual.none { Files.isSymbolicLink(it) },
        ) { "Invalid extension tree" }
        require(actual.count { !Files.isDirectory(it, NOFOLLOW_LINKS) } == files.size + 1) {
            "Extension inventory differs"
        }
        var size = 0L
        for ((relative, expected) in files) {
            currentCoroutineContext().ensureActive()
            val file = extensionTarget(folder, relative)
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Extension file is missing" }
            size += Files.size(file)
            require(size <= MAX_CACHE_BYTES) { "Extension cache is too large" }
            require(extensionDigest(file) == expected.jsonPrimitive.content) { "Extension file differs" }
        }
        validateExtension(folder)
        return folder
    }

    /** Publishes only a fully checked archive, retaining previous installations used by other browser processes. */
    fun activate(staging: Path, files: Map<String, String>): Path {
        val marker = buildJsonObject {
            put("sha256", release.artifact.download.sha256)
            put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
        }
        Files.writeString(staging.resolve(COMPLETION), marker.toString(), CREATE_NEW)
        // Keep the full random identity without hyphens; long resource paths must fit Chromium MAX_PATH.
        val name = "e-${UUID.randomUUID().toString().replace("-", "")}"
        val folder = root.resolve(name)
        Files.move(staging, folder, ATOMIC_MOVE)
        val pointer = root.resolve(".active-${UUID.randomUUID()}")
        try {
            Files.writeString(pointer, name, CREATE_NEW)
            Files.move(pointer, root.resolve("active"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pointer)
        }
        return folder
    }
}

/** Checks the identity trusted by the native host and the executable side-panel payload. */
internal fun validateExtension(folder: Path) {
    val manifest = folder.resolve("manifest.json")
    require(Files.isRegularFile(manifest, NOFOLLOW_LINKS) && Files.size(manifest) in 1..MAX_MANIFEST_BYTES) {
        "Invalid extension manifest"
    }
    val document = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
    require(
        document["manifest_version"]?.jsonPrimitive?.intOrNull == MANIFEST_VERSION,
    ) { "Unsupported extension manifest" }
    val key = Base64.getDecoder().decode(document.getValue("key").jsonPrimitive.content)
    val digest = MessageDigest.getInstance("SHA-256").digest(key).take(16)
    val id = digest.flatMap { byte -> listOf((byte.toInt() ushr 4) and 15, byte.toInt() and 15) }
        .joinToString("") { ('a'.code + it).toChar().toString() }
    require(id in ExtensionIdentity.ids) { "Unexpected extension identity" }
    val panel = document.getValue("side_panel").jsonObject.getValue("default_path").jsonPrimitive.content
    val worker = document.getValue("background").jsonObject.getValue("service_worker").jsonPrimitive.content
    listOf(panel, worker, "app/panel.js").forEach {
        val file = extensionTarget(folder, it)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0) { "Incomplete extension payload" }
    }
    val app = folder.resolve("app")
    require(Files.isDirectory(app, NOFOLLOW_LINKS)) { "Missing extension panel" }
    val hasWasm = Files.list(app).use { paths ->
        paths.anyMatch {
            it.fileName.toString().endsWith(
                ".wasm",
            ) && Files.isRegularFile(it, NOFOLLOW_LINKS) && Files.size(it) > 0
        }
    }
    require(hasWasm) { "Missing extension WebAssembly module" }
}

private const val MAX_MANIFEST_BYTES = 128L * 1024
private const val MAX_MARKER_BYTES = 2L * 1024 * 1024
private const val MAX_ENTRIES = 4096

private const val MAX_CACHE_BYTES = 256L * 1024 * 1024

private const val MAX_POINTER_BYTES = 128L
private const val MANIFEST_VERSION = 3

private const val MAX_CACHED_ENTRIES = MAX_ENTRIES + 1 // The completion marker is not part of the archive.
