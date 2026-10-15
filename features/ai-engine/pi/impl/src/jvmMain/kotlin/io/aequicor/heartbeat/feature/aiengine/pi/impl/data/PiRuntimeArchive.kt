package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream

/** Bounded extraction of the pinned Pi archive into a new directory; links and non-portable paths are refused. */
internal class PiRuntimeArchive(private val maxBytes: Long = MAX_BYTES, private val maxEntries: Int = MAX_ENTRIES) {
    suspend fun extract(archive: Path, root: Path, isZip: Boolean): Map<String, String> {
        val extraction = Extraction(root, isZip)
        if (isZip) extractZip(archive, extraction) else extractTar(archive, extraction)
        return extraction.inventory
    }

    private suspend fun extractZip(archive: Path, extraction: Extraction) {
        ZipFile.builder().setPath(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val item = entries.nextElement()
                val type = item.unixMode and UNIX_TYPE_MASK
                require(type in ZIP_TYPES && !item.isUnixSymlink && zip.canReadEntryData(item)) {
                    "Unsupported Pi zip entry"
                }
                zip.getInputStream(item).use { input ->
                    extraction.entry(input, item.name.removeSuffix("/"), item.size, item.isDirectory, false)
                }
            }
        }
    }

    private suspend fun extractTar(archive: Path, extraction: Extraction) {
        Files.newInputStream(archive).use { input ->
            TarArchiveInputStream(GZIPInputStream(input)).use { tar ->
                var item = tar.nextEntry
                while (item != null) {
                    require(item.linkFlag in TAR_TYPES && !item.isSparse) { "Unsupported Pi tar entry" }
                    extraction.entry(
                        tar,
                        item.name.removeSuffix("/"),
                        item.size,
                        item.isDirectory,
                        item.mode and EXECUTABLE != 0,
                    )
                    item = tar.nextEntry
                }
            }
        }
    }

    private inner class Extraction(private val root: Path, private val isZip: Boolean) {
        val inventory = linkedMapOf<String, String>()
        private val seen = mutableSetOf<String>()
        private var bytes = 0L

        suspend fun entry(input: InputStream, name: String, size: Long, directory: Boolean, executable: Boolean) {
            currentCoroutineContext().ensureActive()
            require(seen.add(name) && seen.size <= maxEntries) { "Duplicate or excessive Pi archive entries" }
            require(size in 0..maxBytes && bytes <= maxBytes - size) { "Pi archive is too large" }
            piRuntimeTarget(root, name)
            val relative = if (isZip) name else name.substringAfter('/', "")
            // The single top-level directory in tar archives is not copied.
            if (relative.isEmpty()) {
                require(directory && size == 0L) { "Invalid Pi archive root" }
                return
            }
            val target = piRuntimeTarget(root, relative)
            if (directory) {
                require(size == 0L) { "Pi archive directory has payload" }
                Files.createDirectories(target)
            } else {
                inventory[relative] = copyEntry(input, target, size)
                bytes += size
                if (executable) check(target.toFile().setExecutable(true, false)) { "Pi file is not executable" }
            }
        }
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
                require(count <= size) { "Pi archive entry exceeds declared size" }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        require(count == size) { "Incomplete Pi archive entry" }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/** Portable relative cache names only: never a drive, absolute path, link or parent traversal. */
internal fun piRuntimeTarget(root: Path, name: String): Path {
    require(
        name.isNotEmpty() && name.none { it == '\\' || it == ':' || it.isISOControl() },
    ) { "Invalid Pi runtime path" }
    require(name.split('/').none { it in setOf("", ".", "..") }) { "Invalid Pi runtime path segment" }
    return root.resolve(name).normalize().also {
        require(it.startsWith(root.normalize()) && it != root.normalize()) { "Pi runtime path escapes its root" }
    }
}

/** Revalidates cached files off the UI thread before starting a downloaded executable. */
internal suspend fun piRuntimeDigest(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
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

private val TAR_TYPES = setOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_DIR)
private const val UNIX_TYPE_MASK = 0xF000
private val ZIP_TYPES = setOf(0, 0x8000, 0x4000)
private const val EXECUTABLE = 0b001001001
private const val MAX_BYTES = 1L shl 30
private const val MAX_ENTRIES = 10_000
private const val BUFFER_BYTES = 64 * 1024
