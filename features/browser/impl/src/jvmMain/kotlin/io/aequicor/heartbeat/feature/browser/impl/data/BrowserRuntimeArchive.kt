package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream

/** Extracts a verified native archive into a new directory, rejecting links, duplicate entries and escaping paths. */
internal class BrowserRuntimeArchive(
    private val maxBytes: Long = MAX_EXTRACTED_BYTES,
    private val maxEntries: Int = MAX_ARCHIVE_ENTRIES,
) {
    /** Returns the exact regular-file inventory and digests for subsequent offline cache validation. */
    suspend fun extract(input: InputStream, root: Path): Map<String, String> {
        val files = linkedMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var total = 0L
        TarArchiveInputStream(GZIPInputStream(input)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                val item = entry
                val name = item.name.removeSuffix("/")
                val target = browserRuntimeTarget(root, name)
                require(seen.add(name) && seen.size <= maxEntries) { "Duplicate or excessive browser archive entries" }
                require(item.linkFlag in ALLOWED_ENTRY_TYPES) { "Unsupported browser archive entry" }
                require(!item.isDirectory || item.size == 0L) { "Browser archive directories must not contain payload" }
                require(
                    !item.isSymbolicLink && !item.isLink && !item.isSparse,
                ) { "Browser archive links are not supported" }
                require(item.size in 0..maxBytes && total <= maxBytes - item.size) { "Browser archive is too large" }
                if (item.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    files[name] = extractFile(tar, item, target)
                    total += item.size
                }
                entry = tar.nextEntry
            }
        }
        return files
    }

    private suspend fun extractFile(tar: TarArchiveInputStream, item: TarArchiveEntry, target: Path): String {
        Files.createDirectories(target.parent)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var count = 0L
        Files.newOutputStream(target, CREATE_NEW).use { output ->
            var read = tar.read(buffer)
            while (read >= 0) {
                currentCoroutineContext().ensureActive()
                count += read
                require(count <= item.size) { "Browser archive entry exceeds its declared size" }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                read = tar.read(buffer)
            }
        }
        require(count == item.size) { "Browser archive entry is incomplete" }
        if (item.mode and EXECUTABLE_BITS != 0) {
            check(target.toFile().setExecutable(true, false)) { "Cannot mark browser helper executable" }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/** Only portable relative names can address a file under a runtime's immutable directory. */
internal fun browserRuntimeTarget(root: Path, name: String): Path {
    require(name.isNotEmpty() && !name.startsWith('/') && name.none { it == '\\' || it == ':' || it.isISOControl() }) {
        "Invalid browser runtime path"
    }
    require(name.split('/').none { it in setOf("", ".", "..") }) { "Invalid browser runtime path segment" }
    val target = root.resolve(name).normalize()
    require(
        target.startsWith(root.normalize()) && target != root.normalize(),
    ) { "Browser runtime path escapes its root" }
    return target
}

/** Native metadata must describe the same API and host that will load it. */
internal fun validateBrowserRuntime(root: Path, releaseTag: String, platform: String) {
    val metadataFile = root.resolve("build_meta.json")
    require(Files.size(metadataFile) <= MAX_METADATA_BYTES) { "Browser native metadata is too large" }
    val metadata = Json.parseToJsonElement(Files.readString(metadataFile)).jsonObject
    require(metadata["release_tag"]?.jsonPrimitive?.content == releaseTag) { "Browser native version differs" }
    require(metadata["platform"]?.jsonPrimitive?.content == platform) { "Browser native platform differs" }
    val required = when {
        platform.startsWith("macosx-") -> listOf(
            "libjcef.dylib",
            "jcef Helper.app/Contents/MacOS/jcef Helper",
            "Chromium Embedded Framework.framework/Chromium Embedded Framework",
        )

        platform.startsWith(
            "windows-",
        ) -> listOf("jcef.dll", "libcef.dll", "jcef_helper.exe", "icudtl.dat", "resources.pak")

        else -> listOf(
            "libjcef.so",
            "libcef.so",
            "jcef_helper",
            "icudtl.dat",
            "resources.pak",
            "v8_context_snapshot.bin",
            "locales/en-US.pak",
        )
    }
    require(
        required.all { Files.isRegularFile(root.resolve(it), NOFOLLOW_LINKS) && Files.size(root.resolve(it)) > 0 },
    ) {
        "Browser runtime is missing required files"
    }
    val helper = when {
        platform.startsWith("macosx-") -> "jcef Helper.app/Contents/MacOS/jcef Helper"
        platform == "linux-amd64" -> "jcef_helper"
        else -> null
    }
    require(helper == null || Files.isExecutable(root.resolve(helper))) { "Browser helper is not executable" }
}

/** Hashes native files off the UI thread; the first use in a process validates the complete cached inventory. */
internal suspend fun browserRuntimeDigest(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
            currentCoroutineContext().ensureActive()
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

private val ALLOWED_ENTRY_TYPES = setOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_DIR)

private const val EXECUTABLE_BITS = 0b001001001

private const val MAX_EXTRACTED_BYTES = 2L shl 30
private const val MAX_ARCHIVE_ENTRIES = 10_000
private const val MAX_METADATA_BYTES = 32 * 1024
private const val COPY_BUFFER_BYTES = 64 * 1024
