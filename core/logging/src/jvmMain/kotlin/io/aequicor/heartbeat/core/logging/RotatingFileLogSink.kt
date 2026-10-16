package io.aequicor.heartbeat.core.logging

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.ErrorManager
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * UTF-8 desktop diagnostics, bounded to five files per concurrently running process by default.
 * JUL's unique `%u` suffix and file lock separate parallel app launches. Messages and rendered throwable
 * stacks are redacted before reaching the handler. Expired records are removed at startup and once per
 * minute, including in idle processes; file modification times never determine record retention.
 * IO failures are reported through JUL's ErrorManager without recursing through the project logger.
 */
public class RotatingFileLogSink internal constructor(
    directory: Path,
    private val maxBytes: Int,
    private val fileCount: Int,
    private val clock: Clock,
    private val legacyDirectories: List<Path>,
) : LogSink,
    AutoCloseable {
    /** Creates the production writer; custom directories do not implicitly include platform legacy logs. */
    public constructor(
        directory: Path,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        fileCount: Int = DEFAULT_FILE_COUNT,
        legacyDirectories: List<Path> = emptyList(),
    ) : this(directory, maxBytes, fileCount, Clock.systemUTC(), legacyDirectories)

    private val storageDirectory = Files.createDirectories(directory).toRealPath()
    private val lock = Any()
    private val failures = FileLogFailures()
    private var isClosed = false
    private var handler: FileHandler? = withLogDirectoryLock(storageDirectory) {
        pruneLogRecords(storageDirectory, clock.instant().minus(LOG_RETENTION))
        createHandler()
    }
    init {
        removeExpiredLegacyRecords()
    }

    private val maintenance = createMaintenance()

    @HighFrequency
    override fun write(level: LogLevel, tag: String, error: Throwable?, message: String) {
        val line = buildString {
            append(clock.instant()).append(" [").append(level).append("] ")
            append(tag).append(" - ").append(message.replace("\r", "\\r").replace("\n", "\\n"))
            error?.let { appendLine().append(it.stackTraceToString()) }
        }
        reportFileFailure {
            synchronized(lock) {
                if (!isClosed) {
                    withLogDirectoryLock(storageDirectory) {
                        handler?.publish(LogRecord(Level.INFO, Redactor.redact(line)))
                        handler?.flush()
                    }
                }
            }
        }
    }

    /** Stops the idle-retention worker and releases JUL's per-process lock. */
    override fun close() {
        maintenance.shutdownNow()
        synchronized(lock) {
            if (!isClosed) {
                isClosed = true
                reportFileFailure {
                    try {
                        withLogDirectoryLock(storageDirectory) { closeHandler() }
                    } finally {
                        closeHandler()
                    }
                }
            }
        }
    }

    internal fun removeExpiredRecords() {
        reportFileFailure {
            synchronized(lock) {
                if (!isClosed) {
                    withLogDirectoryLock(storageDirectory) {
                        closeHandler()
                        try {
                            pruneLogRecords(storageDirectory, clock.instant().minus(LOG_RETENTION))
                        } finally {
                            handler = createHandler()
                        }
                    }
                    removeExpiredLegacyRecords()
                }
            }
        }
    }

    private fun createMaintenance(): ScheduledExecutorService {
        var executor: ScheduledExecutorService? = null
        try {
            executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "heartbeat-log-retention").apply { isDaemon = true }
            }
            executor.scheduleWithFixedDelay(::removeExpiredRecords, 1, 1, TimeUnit.MINUTES)
            return executor
        } catch (e: SecurityException) {
            executor?.shutdownNow()
            closeHandler()
            throw e
        }
    }

    private fun removeExpiredLegacyRecords() {
        legacyDirectories.filter { Files.isDirectory(it) }.forEach { legacy ->
            reportFileFailure {
                withLogDirectoryLock(legacy) { pruneLogRecords(legacy, clock.instant().minus(LOG_RETENTION)) }
            }
        }
    }

    private fun closeHandler() {
        val current = handler ?: return
        current.close()
        handler = null
        unregisterLocalJulHandler(storageDirectory)
    }

    private fun createHandler(): FileHandler {
        val created = FileHandler(
            storageDirectory.resolve("heartbeat-%u-%g.log").toString(),
            maxBytes,
            fileCount,
            true,
        )
        try {
            created.encoding = Charsets.UTF_8.name()
            created.formatter = object : Formatter() {
                override fun format(record: LogRecord): String = record.message + System.lineSeparator()
            }
        } catch (e: IOException) {
            created.close()
            throw e
        } catch (e: SecurityException) {
            created.close()
            throw e
        }
        registerLocalJulHandler(storageDirectory)
        return created
    }

    private fun reportFileFailure(action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            failures.fail(e)
        } catch (e: SecurityException) {
            failures.fail(e)
        }
    }

    /** Rotation defaults are independent of the selected application log level. */
    public companion object {
        public const val DEFAULT_MAX_BYTES: Int = 5 * 1024 * 1024
        public const val DEFAULT_FILE_COUNT: Int = 5
    }
}

/** Reports low-level sink failures outside Log to avoid recursive failures when the disk is unavailable. */
private class FileLogFailures {
    private val errorManager = ErrorManager()

    fun fail(error: Exception) {
        errorManager.error(
            "Desktop log storage failed",
            IOException(error.redactedFailure()),
            ErrorManager.WRITE_FAILURE,
        )
    }
}
