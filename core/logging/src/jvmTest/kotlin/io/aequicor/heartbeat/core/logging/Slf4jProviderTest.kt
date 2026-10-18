package io.aequicor.heartbeat.core.logging

import org.slf4j.LoggerFactory
import org.slf4j.spi.SLF4JServiceProvider
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.ServiceLoader
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Slf4jProviderTest {
    @Test
    fun `service loaded provider sends formatted warnings and errors through project sinks`() {
        val providers = ServiceLoader.load(SLF4JServiceProvider::class.java).toList()
        assertEquals(1, providers.size)
        assertIs<Slf4jProvider>(providers.single())
        val entries = mutableListOf<Pair<LogLevel, String>>()
        val errors = mutableListOf<Throwable?>()
        Log.init(
            isDebug = false,
            sinks = listOf(
                LogSink { level, _, error, message ->
                    entries += level to message
                    errors += error
                },
            ),
        )
        try {
            val logger = LoggerFactory.getLogger("library")
            assertSame(logger, LoggerFactory.getLogger("library"))
            logger.info("routine diagnostics")
            logger.warn("warning {}", "details")
            logger.warn("Authorization: Bearer {}", "private-token")
            val failure = IllegalStateException("private exception message")
            logger.error("library failed", failure)
            assertEquals(listOf(LogLevel.WARNING, LogLevel.WARNING, LogLevel.ERROR), entries.map { it.first })
            assertEquals("warning details", entries.first().second)
            assertFalse(entries[1].second.contains("private-token"))
            assertEquals("library failed", entries.last().second)
            val logged = errors.last()!!
            assertFalse(logged.stackTraceToString().contains("private exception message"))
            assertTrue(logged.stackTrace.contentEquals(failure.stackTrace))
        } finally {
            Log.init(isDebug = false)
        }
    }

    @Test
    fun `failed ordinary and fluent argument conversion never writes raw failures to stderr`() {
        val entries = mutableListOf<String>()
        val errors = mutableListOf<Throwable?>()
        val stderr = ByteArrayOutputStream()
        val originalStderr = System.err
        val failing = object {
            override fun toString(): String = error("private-url-and-user-text")
        }
        Log.init(
            isDebug = false,
            sinks = listOf(
                LogSink { _, _, error, message ->
                    entries += message
                    errors += error
                },
            ),
        )
        try {
            System.setErr(PrintStream(stderr))
            val logger = LoggerFactory.getLogger("unsafe-arguments")
            logger.warn("unused argument", failing)
            logger.warn("escaped \\{}", failing)
            logger.warn("ordinary {}", failing)
            val failingError = object {
                override fun toString(): String = throw AssertionError("private-url-and-user-text")
            }
            logger.warn("error argument {}", failingError)
            logger.atWarn().addKeyValue("field", failing).log("fluent")
            logger.atWarn().addArgument(failing).log("fluent argument {}")
            assertEquals("", stderr.toString())
            assertTrue(entries.contains("ordinary [FAILED toString()]"))
            assertTrue(entries.contains("field=[FAILED toString()] fluent"))
            assertTrue(entries.contains("fluent argument [FAILED toString()]"))
            val output = (entries + errors.map { it?.stackTraceToString().orEmpty() }).joinToString()
            assertFalse(output.contains("private-url-and-user-text"))
            assertEquals(4, errors.count { it != null })
        } finally {
            System.setErr(originalStderr)
            Log.init(isDebug = false)
        }
    }

    @Test
    fun `array formatting preserves nested primitive arrays cycles and escaped placeholders`() {
        val entries = mutableListOf<String>()
        Log.init(isDebug = false, sinks = listOf(LogSink { _, _, _, message -> entries += message }))
        try {
            val logger = LoggerFactory.getLogger("arrays")
            val cycle = arrayOfNulls<Any>(1).apply { this[0] = this }
            val nested = arrayOf<Any?>(intArrayOf(1, 2), arrayOf("three", null), cycle)
            logger.warn("arrays {}", nested as Any)
            logger.warn("escaped \\{} {}", "one", "two")
            assertEquals("arrays [[1, 2], [three, null], [...]]", entries[0])
            assertEquals("escaped {} one", entries[1])
        } finally {
            Log.init(isDebug = false)
        }
    }

    @Test
    fun `normalized throwable arguments remain available alongside the real cause`() {
        val entries = mutableListOf<String>()
        val errors = mutableListOf<Throwable?>()
        Log.init(
            isDebug = false,
            sinks = listOf(
                LogSink { _, _, error, message ->
                    entries += message
                    errors += error
                },
            ),
        )
        try {
            val logger = LoggerFactory.getLogger("throwable-arguments")
            val argument = IllegalArgumentException("argument detail")
            val cause = IllegalStateException("private cause detail")
            logger.warn("value {}", argument as Any)
            logger.error("value {}", argument, cause)
            logger.atError().setCause(cause).addArgument(argument).log("fluent {}")
            assertEquals("value java.lang.IllegalArgumentException: argument detail", entries[0])
            assertEquals("value java.lang.IllegalArgumentException: argument detail", entries[1])
            assertEquals("fluent java.lang.IllegalArgumentException: argument detail", entries[2])
            assertEquals(null, errors[0])
            errors.drop(1).forEach {
                assertTrue(it!!.stackTrace.contentEquals(cause.stackTrace))
                assertFalse(it.stackTraceToString().contains("private cause detail"))
            }
        } finally {
            Log.init(isDebug = false)
        }
    }

    @Test
    fun `argument conversion propagates cancellation`() {
        Log.init(isDebug = false, sinks = listOf(LogSink { _, _, _, _ -> }))
        try {
            val cancelled = object {
                override fun toString(): String = throw CancellationException("cancelled")
            }
            assertFailsWith<CancellationException> {
                LoggerFactory.getLogger("cancelled-argument").warn("value {}", cancelled)
            }
        } finally {
            Log.init(isDebug = false)
        }
    }
}
