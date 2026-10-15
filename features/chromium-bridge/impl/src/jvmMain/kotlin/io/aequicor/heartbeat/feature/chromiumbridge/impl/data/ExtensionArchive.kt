package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat

/** Extracts only portable regular files into a fresh directory, with declared and actual byte limits. */
internal class ExtensionArchive(private val maxBytes: Long = MAX_BYTES, private val maxEntries: Int = MAX_ENTRIES) {
    suspend fun extract(archive: Path, root: Path): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipFile.builder().setPath(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = entries.nextElement()
                require(entry.rawName?.contains('\\'.code.toByte()) != true) { "Invalid extension raw path" }
                val name = entry.name.removeSuffix("/")
                val target = extensionTarget(root, name)
                val type = entry.unixMode and UNIX_TYPE_MASK
                require(!entry.isUnixSymlink && zip.canReadEntryData(entry) && type in ZIP_TYPES) {
                    "Unsupported extension archive entry"
                }
                require(seen.add(name) && seen.size <= maxEntries) { "Duplicate or excessive extension entries" }
                require(
                    entry.size in 0..maxBytes && total <= maxBytes - entry.size,
                ) { "Extension archive is too large" }
                if (entry.isDirectory) {
                    require(entry.size == 0L && type != UNIX_FILE) { "Invalid extension directory" }
                    Files.createDirectories(target)
                } else {
                    require(type != UNIX_DIRECTORY && name != COMPLETION) { "Invalid extension file" }
                    zip.getInputStream(entry).use { input ->
                        hashes[name] = copyEntry(input, target, entry.size)
                    }
                    total += entry.size
                }
            }
        }
        return hashes
    }

    private suspend fun copyEntry(input: InputStream, target: Path, size: Long): String {
        Files.createDirectories(target.parent)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newOutputStream(target, CREATE_NEW).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var read = input.read(buffer)
            while (read >= 0) {
                currentCoroutineContext().ensureActive()
                count += read
                require(count <= size) { "Extension entry exceeds declared size" }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        require(count == size) { "Incomplete extension file" }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/** Cache and archive paths share the same strict portable relative-name rules. */
internal fun extensionTarget(root: Path, name: String): Path {
    require(
        name.isNotEmpty() && name.none { it == '\\' || it == ':' || it.isISOControl() },
    ) { "Invalid extension path" }
    require(name.split('/').none { it in setOf("", ".", "..") }) { "Invalid extension path segment" }
    return root.resolve(name).normalize().also {
        require(it.startsWith(root.normalize()) && it != root.normalize()) { "Extension path escapes its root" }
    }
}

/** Cache integrity checks are cancellable and run off the UI thread. */
internal suspend fun extensionDigest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
            currentCoroutineContext().ensureActive()
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

internal const val COMPLETION = ".complete.json"
private const val BUFFER_BYTES = 64 * 1024
private const val UNIX_TYPE_MASK = 0xF000
private const val UNIX_FILE = 0x8000
private const val UNIX_DIRECTORY = 0x4000
private val ZIP_TYPES = setOf(0, UNIX_FILE, UNIX_DIRECTORY)

private const val MAX_BYTES = 256L * 1024 * 1024
private const val MAX_ENTRIES = 4096
