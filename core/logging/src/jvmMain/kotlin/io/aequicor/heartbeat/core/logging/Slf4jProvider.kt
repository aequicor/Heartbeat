package io.aequicor.heartbeat.core.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/** Desktop service-loaded backend: library diagnostics use the same redaction and sinks as application logs. */
internal class Slf4jProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val factory = ILoggerFactory { name -> loggers.computeIfAbsent(name, ::Slf4jLogger) }
    private val markers = BasicMarkerFactory()
    private val context = BasicMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = context
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}

/** Routine library diagnostics are trace-only; failures retain their severity and sanitized original stack. */
private class Slf4jLogger(private val loggerName: String) : LegacyAbstractLogger() {
    private val log = Log.tag("SLF4J/$loggerName")

    override fun getName(): String = loggerName
    override fun getFullyQualifiedCallerName(): String = Slf4jLogger::class.java.name
    override fun isTraceEnabled(): Boolean = true
    override fun isDebugEnabled(): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

    @HighFrequency
    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val message = { MessageFormatter.arrayFormat(messagePattern, arguments).message.orEmpty() }
        when (level) {
            Level.TRACE, Level.DEBUG, Level.INFO -> log.v(message)
            Level.WARN -> log.w(throwable?.redactedFailure(), message)
            Level.ERROR -> log.e(throwable?.redactedFailure(), message)
        }
    }
}
