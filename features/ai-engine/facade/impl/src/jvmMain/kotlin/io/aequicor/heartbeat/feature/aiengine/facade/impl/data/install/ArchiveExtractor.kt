package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Unpacks a verified release file into an empty directory and never writes outside it: absolute paths, drive
 * letters, backslashes, `..` segments and control characters are refused, links and special files are refused,
 * and the entry count and unpacked size are bounded. Entries with an executable bit stay executable on POSIX hosts.
 * Failures are [io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException]s; the caller discards the
 * directory.
 */
class ArchiveExtractor(private val limits: ExtractionLimits = ExtractionLimits()) {
    private val log = Log.tag("ArchiveExtractor")

    /** Unpacks [archive] as [kind] into the existing, empty [destination]. */
    fun extract(archive: Path, kind: ArchiveKind, destination: Path) {
        log.i { "unpack release archive kind=$kind" }
        val root = destination.toAbsolutePath().normalize()
        val budget = Budget(limits)
        try {
            when (kind) {
                is ArchiveKind.TarGz -> extractTar(archive, kind.stripComponents, root, budget)
                is ArchiveKind.Zip -> extractZip(archive, kind.stripComponents, root, budget)
                is ArchiveKind.Raw -> extractRaw(archive, kind.fileName, root, budget)
            }
        } catch (e: IOException) {
            throw unpackFailure(e)
        }
        log.i { "release archive unpacked entries=${budget.entries} bytes=${budget.bytes}" }
    }

    /** A damaged archive or a repeated entry is an invalid archive; anything else could not be written. */
    private fun unpackFailure(error: IOException): Exception {
        val isInvalid = error is ZipException || error is FileAlreadyExistsException
        log.w(error.withoutDetails()) {
            if (isInvalid) "release archive is damaged" else "release archive could not be unpacked"
        }
        val reason = if (isInvalid) InstallFailureReason.InvalidArchive else InstallFailureReason.Storage
        return installFailure(reason, error.withoutDetails())
    }

    private fun extractTar(archive: Path, strip: Int, root: Path, budget: Budget) {
        Files.newInputStream(archive).buffered().use { input ->
            GZIPInputStream(input).use { stream ->
                extractTarEntries(TarReader(stream), strip, root, budget)
            }
        }
    }

    private fun extractTarEntries(reader: TarReader, strip: Int, root: Path, budget: Budget) {
        generateSequence { reader.next() }.forEach { entry ->
            budget.entry(entry.size)
            budget.add(entry.size)
            if (entry.type == TarEntryType.Special) refuse("special archive entry")
            val target = stripped(entry.name, strip)?.let { safeTarget(root, it) } ?: return@forEach
            if (entry.type == TarEntryType.Directory) {
                Files.createDirectories(target)
            } else {
                write(target) { output -> reader.copyTo(output) }
                if (entry.mode and EXECUTE_BITS != 0) markExecutable(target)
            }
        }
    }

    private fun extractZip(archive: Path, strip: Int, root: Path, budget: Budget) {
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                budget.entry(entry.size)
                val relative = stripped(entry.name, strip) ?: return@forEach
                val target = safeTarget(root, relative)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    zip.getInputStream(
                        entry,
                    ).use { input -> write(target) { output -> copyBounded(input, output, budget) } }
                }
            }
        }
    }

    private fun extractRaw(archive: Path, fileName: String, root: Path, budget: Budget) {
        val size = Files.size(archive)
        budget.entry(size)
        budget.add(size)
        val target = safeTarget(root, fileName)
        Files.newInputStream(archive).use { input -> write(target) { output -> input.copyTo(output) } }
        markExecutable(target)
    }

    private fun write(target: Path, copy: (OutputStream) -> Unit) {
        target.parent?.let(Files::createDirectories)
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use(copy)
    }

    /** Zip entries may lie about their size; the copy itself enforces the per-entry and total limits. */
    private fun copyBounded(input: InputStream, output: OutputStream, budget: Budget) {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > limits.maxEntryBytes) refuse("entry exceeds the size limit")
            budget.add(read.toLong())
            output.write(buffer, 0, read)
        }
    }

    /** The name without its first [strip] segments; the whole original name must be safe, not only the rest. */
    private fun stripped(name: String, strip: Int): String? {
        if (isUnsafeName(name)) refuse("unsafe entry name")
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        return segments.drop(strip).takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun refuse(reason: String): Nothing {
        log.w { "release archive refused: $reason" }
        throw installFailure(InstallFailureReason.InvalidArchive)
    }

    /** Counts entries and unpacked bytes of one extraction; [add] gets the bytes actually written. */
    private inner class Budget(private val limits: ExtractionLimits) {
        var entries = 0
        var bytes = 0L

        /** One more entry announcing [declaredSize] bytes (negative when unknown). */
        fun entry(declaredSize: Long) {
            entries++
            if (entries > limits.maxEntries) refuse("too many entries")
            if (declaredSize > limits.maxEntryBytes) refuse("entry exceeds the size limit")
        }

        fun add(size: Long) {
            bytes += size
            if (bytes > limits.maxTotalBytes) refuse("unpacked size exceeds the limit")
        }
    }

    private companion object {
        const val EXECUTE_BITS = 0b001_001_001
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** Bounds of one extraction. */
data class ExtractionLimits(
    val maxEntries: Int = MAX_ENTRIES,
    val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    val maxTotalBytes: Long = MAX_TOTAL_BYTES,
)

private const val MAX_ENTRIES = 20_000
private const val MAX_ENTRY_BYTES = 1L shl 30
private const val MAX_TOTAL_BYTES = 2L shl 30

/**
 * [relative] resolved under [root], refusing anything that could leave it: absolute paths, drive letters,
 * backslashes, `..` segments and control characters.
 */
internal fun safeTarget(root: Path, relative: String): Path {
    if (relative.isEmpty() || isUnsafeName(relative)) throw installFailure(InstallFailureReason.InvalidArchive)
    val target = relative.split('/').filter { it.isNotEmpty() && it != "." }.fold(root, Path::resolve).normalize()
    if (target == root || !target.startsWith(root)) throw installFailure(InstallFailureReason.InvalidArchive)
    return target
}

/** Marks [path] executable for everyone on POSIX hosts; Windows decides by extension. */
internal fun markExecutable(path: Path) {
    if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
    Files.setPosixFilePermissions(path, ExecutablePermissions)
}

/** Absolute, drive-relative, backslashed, control-character or `..` names could leave the unpacking root. */
private fun isUnsafeName(name: String): Boolean =
    name.startsWith('/') || name.any { it == '\\' || it.isISOControl() } || DriveLetter.containsMatchIn(name) ||
        name.split('/').any { it == ".." }

private val DriveLetter = Regex("^[A-Za-z]:")

private val ExecutablePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_EXECUTE,
)
