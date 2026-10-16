package io.aequicor.heartbeat.core.logging

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Blocking desktop diagnostics IO; callers must use their IO dispatcher. Exports include only recognized
 * Heartbeat logs, redact legacy content again and preserve complete throwable records. The ten-minute
 * window includes both endpoints. Archives stream retained records without loading all files into memory.
 * Damaged UTF-8 bytes are replaced with U+FFFD without discarding surrounding records.
 * Active legacy writers are never modified; their expired records are still excluded from exports.
 */
public class DesktopLogFiles(
    /** Current desktop log destination, shared with the writer and the open-folder action. */
    public val directory: Path,
    private val legacyDirectories: List<Path> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Uses the host's current and historical application log locations. */
    public constructor() : this(desktopLogDirectory(), desktopLegacyLogDirectories())

    /** Returns an empty string when the available files have no records from the last ten minutes. */
    public fun recentText(): String {
        val now = clock.instant()
        val records = mutableListOf<TimedLogRecord>()
        forEachSnapshot { _, files ->
            files.forEach { file -> readRecentRecords(file, now, records) }
        }
        return records.sortedBy(TimedLogRecord::time).joinToString("") { it.text }
    }

    /** Saves the same ten-minute UTF-8 record selection as [recentText]. */
    public fun writeRecent(destination: Path) {
        validateExportDestination(destination)
        Files.writeString(destination, recentText(), Charsets.UTF_8)
    }

    /** Writes a valid ZIP, including when empty, containing at most the last five days of records. */
    public fun writeArchive(destination: Path) {
        validateExportDestination(destination)
        val now = clock.instant()
        ZipOutputStream(Files.newOutputStream(destination)).use { zip ->
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            forEachSnapshot { index, files ->
                files.forEach { file ->
                    val hasRecords = copyLogRecords(file, writer, now.minus(LOG_RETENTION), now) {
                        zip.putNextEntry(ZipEntry("logs-$index/${file.fileName}"))
                    }
                    if (hasRecords) {
                        writer.flush()
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun readRecentRecords(file: Path, now: Instant, records: MutableList<TimedLogRecord>) {
        var time: Instant? = null
        val text = StringBuilder()
        fun completeRecord() {
            time?.let { records.add(TimedLogRecord(it, text.toString())) }
            text.clear()
        }
        openLogReader(file).use { reader ->
            reader.lineSequence().forEach { line ->
                recordTime(line)?.let { timestamp ->
                    completeRecord()
                    time = timestamp.takeIf { it >= now.minus(RECENT_WINDOW) && it <= now }
                }
                if (time != null) text.append(Redactor.redact(line)).append('\n')
            }
        }
        completeRecord()
    }

    private fun forEachSnapshot(action: (Int, List<Path>) -> Unit) {
        val temporary = Files.createTempDirectory("heartbeat-log-snapshot-")
        try {
            val snapshots = directories().mapIndexedNotNull { index, source ->
                if (!Files.isDirectory(source)) return@mapIndexedNotNull null
                val destination = Files.createDirectory(temporary.resolve(index.toString()))
                val files = withLogDirectoryLock(source) {
                    logFiles(source).map { file -> Files.copy(file, destination.resolve(file.fileName)) }
                }
                index to files
            }
            snapshots.forEach { (index, files) -> action(index, files) }
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun directories(): List<Path> = (listOf(directory) + legacyDirectories)
        .map { if (Files.exists(it)) it.toRealPath() else it.toAbsolutePath().normalize() }
        .distinct()

    /** Rejects overwrite targets inside current or legacy storage, including symbolic-link aliases. */
    public fun validateExportDestination(destination: Path) {
        val target = if (Files.exists(destination)) {
            destination.toRealPath()
        } else {
            destination.toAbsolutePath().parent.toRealPath().resolve(destination.fileName)
        }
        require(directories().filter { Files.exists(it) }.none { target.startsWith(it.toRealPath()) }) {
            "Exports must be saved outside the log directory"
        }
    }

    private companion object {
        val RECENT_WINDOW: Duration = Duration.ofMinutes(10)
    }
}

private data class TimedLogRecord(val time: Instant, val text: String)
