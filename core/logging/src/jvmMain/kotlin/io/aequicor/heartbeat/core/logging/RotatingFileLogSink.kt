package io.aequicor.heartbeat.core.logging

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * UTF-8 desktop diagnostics, with five bounded files per concurrently running process by default.
 * JUL's unique `%u` suffix and file lock separate parallel app launches. Messages and rendered throwable
 * stacks are redacted before reaching the handler. Its ErrorManager reports disk failures to stderr without
 * calling the project logger recursively or propagating them into application operations.
 */
public class RotatingFileLogSink(
    directory: Path,
    maxBytes: Int = DEFAULT_MAX_BYTES,
    fileCount: Int = DEFAULT_FILE_COUNT,
) : LogSink,
    AutoCloseable {
    private val lock = Any()
    private var isClosed = false
    private val handler = FileHandler(
        Files.createDirectories(directory).resolve("heartbeat-%u-%g.log").toString(),
        maxBytes,
        fileCount,
        true,
    ).apply {
        encoding = Charsets.UTF_8.name()
        formatter = object : Formatter() {
            override fun format(record: LogRecord): String = record.message + System.lineSeparator()
        }
    }

    override fun write(level: LogLevel, tag: String, error: Throwable?, message: String) {
        val line = buildString {
            append(Instant.now()).append(" [").append(level).append("] ")
            append(tag).append(" - ").append(message.replace("\r", "\\r").replace("\n", "\\n"))
            error?.let { appendLine().append(it.stackTraceToString()) }
        }
        synchronized(lock) {
            if (!isClosed) {
                handler.publish(LogRecord(Level.INFO, Redactor.redact(line)))
                handler.flush()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                isClosed = true
                handler.close()
            }
        }
    }

    /** Rotation defaults are independent of the selected application log level. */
    public companion object {
        public const val DEFAULT_MAX_BYTES: Int = 5 * 1024 * 1024
        public const val DEFAULT_FILE_COUNT: Int = 5
    }
}
