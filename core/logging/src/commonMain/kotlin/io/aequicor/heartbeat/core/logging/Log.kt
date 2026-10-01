package io.aequicor.heartbeat.core.logging

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.Napier
import kotlin.concurrent.Volatile
import io.github.aakira.napier.LogLevel as NapierLevel

/** Project log levels, ordered by severity. */
public enum class LogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR }

/** Destination for log records (file, crash reporter). Registered via [Log.init]. */
public fun interface LogSink {
    /** Receives an already filtered and redacted record. */
    public fun write(level: LogLevel, tag: String, error: Throwable?, message: String)
}

/**
 * Project-wide logging facade over Napier — the only allowed logging entry point
 * Messages are lambdas: they are not built when the level is disabled,
 * and every message passes through the secret redactor. The throwable is passed as is — never put secrets
 * into exception messages.
 *
 * ```
 * private val log = Log.tag("ChatRepository")
 * log.v { "load history chatId=$chatId" }
 * log.e(error) { "generation failed chatId=$chatId" }
 * ```
 */
public class Log private constructor(private val tag: String) {

    /** Fine-grained tracing: routine storage IO, streaming revisions, state diffs, lifecycle callbacks. */
    public fun v(message: () -> String): Unit = write(LogLevel.VERBOSE, null, message)

    /** Developer diagnostics: operational decisions and one-shot output delivery. */
    public fun d(message: () -> String): Unit = write(LogLevel.DEBUG, null, message)

    /** Meaningful events: transitions, requests, configuration changes. */
    public fun i(message: () -> String): Unit = write(LogLevel.INFO, null, message)

    /** Recovered failures. Pass the [error] whenever there is one. */
    public fun w(error: Throwable? = null, message: () -> String): Unit = write(LogLevel.WARNING, error, message)

    /** Unrecovered failures. Pass the [error] whenever there is one. */
    public fun e(error: Throwable? = null, message: () -> String): Unit = write(LogLevel.ERROR, error, message)

    private fun write(level: LogLevel, error: Throwable?, message: () -> String) {
        if (level < minLevel) return
        Napier.log(level.toNapier(), tag = tag, throwable = error, message = Redactor.redact(message()))
    }

    /** Entry points: [tag], [init], [redact]. */
    public companion object {
        private const val REDACTED = "***"

        // Global by design, like Napier itself: logging must work before (and outside) the DI graph.
        @Volatile
        private var minLevel: LogLevel = LogLevel.INFO

        /** Logger for [tag]: the source class name, or an infrastructure prefix (`SM/chat`, `NET`, `DI`). */
        public fun tag(tag: String): Log = Log(tag)

        /**
         * Configures output. Called once by the platform entry point before the DI graph is built;
         * calling it again replaces the previous configuration.
         *
         * Debug builds log from `DEBUG` up; routine storage IO, streaming revisions and state/effect internals
         * use `VERBOSE`, enabled by `isTrace`. JVM console records use compact timestamps and UTF-8;
         * reinitialization replaces the console destination without accumulating handlers.
         */
        public fun init(isDebug: Boolean, isTrace: Boolean = false, sinks: List<LogSink> = emptyList()) {
            Napier.takeLogarithm()
            minLevel = when {
                isTrace -> LogLevel.VERBOSE
                isDebug -> LogLevel.DEBUG
                else -> LogLevel.INFO
            }
            if (isDebug) Napier.base(platformDebugAntilog())
            sinks.forEach { Napier.base(SinkAntilog(it)) }
        }

        /** Hides a sensitive value in a log message while keeping the fact that it was present. */
        public fun redact(value: Any?): String = if (value == null) "null" else REDACTED
    }
}

private class SinkAntilog(private val sink: LogSink) : Antilog() {
    override fun performLog(priority: NapierLevel, tag: String?, throwable: Throwable?, message: String?) {
        sink.write(priority.toLogLevel(), tag.orEmpty(), throwable, message.orEmpty())
    }
}

private fun LogLevel.toNapier(): NapierLevel = when (this) {
    LogLevel.VERBOSE -> NapierLevel.VERBOSE
    LogLevel.DEBUG -> NapierLevel.DEBUG
    LogLevel.INFO -> NapierLevel.INFO
    LogLevel.WARNING -> NapierLevel.WARNING
    LogLevel.ERROR -> NapierLevel.ERROR
}

private fun NapierLevel.toLogLevel(): LogLevel = when (this) {
    NapierLevel.VERBOSE -> LogLevel.VERBOSE
    NapierLevel.DEBUG -> LogLevel.DEBUG
    NapierLevel.INFO -> LogLevel.INFO
    NapierLevel.WARNING -> LogLevel.WARNING
    NapierLevel.ERROR, NapierLevel.ASSERT -> LogLevel.ERROR
}
