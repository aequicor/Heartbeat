package io.aequicor.heartbeat.core.logging

import java.io.Writer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.ParsePosition
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.concurrent.ConcurrentHashMap

/**
 * JUL exists before the application DI graph, just like Log. This process-wide ownership counter is
 * confined to low-level file-lock coordination: opening and closing a second channel for a locally
 * held JUL lock can release the original POSIX lock. All updates use the canonical directory monitor.
 * With multiple local sinks, stale foreign lock cleanup waits until the last local owner closes.
 */
private val localJulOwners = ConcurrentHashMap<Path, Int>()

internal fun registerLocalJulHandler(directory: Path) {
    synchronized(directory.toString().intern()) { localJulOwners.merge(directory, 1, Int::plus) }
}

internal fun unregisterLocalJulHandler(directory: Path) {
    synchronized(directory.toString().intern()) {
        localJulOwners.computeIfPresent(directory) { _, count -> (count - 1).takeIf { it > 0 } }
    }
}

internal val LOG_RETENTION: Duration = Duration.ofDays(5)
private val logName = Regex("heartbeat-([0-9]+)-([0-9]+)\\.log")
private val recordHeader = Regex(
    """^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z) \[(?:VERBOSE|DEBUG|INFO|WARNING|ERROR)] """,
)

/** Cooperating writers serialize rotation with retention and bounded export reads across processes. */
internal fun <T> withLogDirectoryLock(directory: Path, action: () -> T): T {
    Files.createDirectories(directory)
    // Interning provides the same JVM monitor without a mutable global lock registry.
    val monitor = directory.toRealPath().toString().intern()
    return synchronized(monitor) {
        FileChannel.open(
            directory.resolve(".heartbeat-logs.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel -> channel.lock().use { action() } }
    }
}

internal fun logFiles(directory: Path): List<Path> = Files.list(directory).use { paths ->
    paths.filter { logName.matches(it.fileName.toString()) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .sorted()
        .toList()
}

/** Streams one line at a time; a timestamped header owns all following throwable lines. */
internal fun copyLogRecords(
    file: Path,
    destination: Writer,
    from: Instant,
    through: Instant,
    onFirstRecord: () -> Unit = {},
): Boolean {
    var isRecordIncluded = false
    var hasRecords = false
    Files.newBufferedReader(file, Charsets.UTF_8).use { reader ->
        reader.lineSequence().forEach { line ->
            recordTime(line)?.let { timestamp ->
                isRecordIncluded = timestamp >= from && timestamp <= through
                if (isRecordIncluded && !hasRecords) {
                    onFirstRecord()
                    hasRecords = true
                }
            }
            if (isRecordIncluded) {
                destination.write(Redactor.redact(line))
                destination.write("\n")
            }
        }
    }
    return hasRecords
}

internal fun recordTime(line: String): Instant? {
    val timestamp = recordHeader.find(line)?.groupValues?.get(1) ?: return null
    val position = ParsePosition(0)
    val parsed = DateTimeFormatter.ISO_INSTANT.parseUnresolved(timestamp, position) ?: return null
    return Instant.ofEpochSecond(
        parsed.getLong(ChronoField.INSTANT_SECONDS),
        parsed.getLong(ChronoField.NANO_OF_SECOND),
    )
}

/** Rewrites only unlocked JUL streams. Each live sink closes its own stream during maintenance. */
internal fun pruneLogRecords(directory: Path, cutoff: Instant) {
    logFiles(
        directory,
    ).groupBy { it.fileName.toString().substringAfter("heartbeat-").substringBefore("-") }.forEach { (id, files) ->
        val lockPath = directory.resolve("heartbeat-$id-0.log.lck")
        if (Files.isSymbolicLink(lockPath)) return@forEach
        // Never even open a second channel for a lock potentially held by this JVM (POSIX semantics).
        if (localJulOwners.containsKey(directory.toRealPath()) && Files.exists(lockPath)) return@forEach
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            tryJulLock(channel).getOrNull()?.use {
                files.forEach { file -> pruneLogFile(file, cutoff) }
            }
        }
    }
}

/** A same-JVM owner is represented as contention just like tryLock returning null in another process. */
private fun tryJulLock(channel: FileChannel): Result<FileLock?> = try {
    Result.success(channel.tryLock())
} catch (e: OverlappingFileLockException) {
    Result.failure(e)
}

private fun pruneLogFile(file: Path, cutoff: Instant) {
    val hasExpiredRecords = Files.newBufferedReader(file, Charsets.UTF_8).use { reader ->
        reader.lineSequence().any { line -> recordTime(line)?.isBefore(cutoff) == true }
    }
    if (!hasExpiredRecords) return
    val temporary = Files.createTempFile(file.parent, ".heartbeat-prune-", ".tmp")
    try {
        val hasRecords = Files.newBufferedWriter(temporary, Charsets.UTF_8).use { writer ->
            copyLogRecords(file, writer, cutoff, Instant.MAX)
        }
        if (hasRecords) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.delete(file)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
