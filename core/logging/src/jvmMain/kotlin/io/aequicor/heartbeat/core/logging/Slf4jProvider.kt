package io.aequicor.heartbeat.core.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.event.LoggingEvent
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.spi.LoggingEventAware
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Array as ReflectArray

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
private class Slf4jLogger(private val loggerName: String) :
    LegacyAbstractLogger(),
    LoggingEventAware {
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
        write(level, throwable) { format(messagePattern, arguments) }
    }

    @HighFrequency
    override fun log(event: LoggingEvent) {
        // Fluent events keep their arguments separate from the explicitly supplied cause.
        write(event.level, event.throwable) {
            buildString {
                event.markers?.forEach { append(safeString(it)).append(' ') }
                event.keyValuePairs?.forEach {
                    append(it.key).append('=').append(safeString(it.value)).append(' ')
                }
                append(format(event.message, event.argumentArray))
            }
        }
    }

    @HighFrequency
    private fun write(level: Level, throwable: Throwable?, message: () -> String) {
        when (level) {
            Level.TRACE, Level.DEBUG, Level.INFO -> log.v(message)
            Level.WARN -> log.w(throwable?.redactedFailure(), message)
            Level.ERROR -> log.e(throwable?.redactedFailure(), message)
        }
    }

    private fun format(pattern: String?, arguments: Array<out Any?>?): String {
        if (pattern == null || arguments == null) return pattern.orEmpty()
        val safeArguments = arrayOfNulls<String>(arguments.size)
        var offset = 0
        var argument = 0
        // Match SLF4J's escaped-placeholder traversal so unused arguments are never evaluated.
        while (argument < arguments.size) {
            val delimiter = pattern.indexOf("{}", offset)
            if (delimiter < 0) break
            val isEscaped = delimiter > 0 && pattern[delimiter - 1] == '\\'
            val isDoubleEscaped = delimiter > 1 && pattern[delimiter - 2] == '\\'
            if (isEscaped && !isDoubleEscaped) {
                offset = delimiter + 1
            } else {
                safeArguments[argument] = safeString(arguments[argument])
                argument++
                offset = delimiter + 2
            }
        }
        // AbstractLogger already separated the cause: never extract a trailing Throwable again.
        return MessageFormatter.arrayFormat(pattern, safeArguments, null).message.orEmpty()
    }

    private fun safeString(value: Any?): String {
        val arrays = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return render(value, arrays)
    }

    private fun render(value: Any?, arrays: MutableSet<Any>): String = when {
        value == null -> "null"

        value.javaClass.isArray -> {
            if (arrays.add(value)) {
                try {
                    (0 until ReflectArray.getLength(value)).joinToString(prefix = "[", postfix = "]") {
                        render(ReflectArray.get(value, it), arrays)
                    }
                } finally {
                    arrays.remove(value)
                }
            } else {
                "..."
            }
        }

        // SLF4J accepts arbitrary objects, including toString implementations that throw JVM Errors.
        else -> runCatching { value.toString() }.getOrElse { error ->
            if (error is CancellationException) throw error
            log.w(error.redactedFailure()) { "SLF4J argument conversion failed" }
            "[FAILED toString()]"
        }
    }
}
