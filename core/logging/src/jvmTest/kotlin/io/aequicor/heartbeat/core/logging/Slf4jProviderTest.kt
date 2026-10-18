package io.aequicor.heartbeat.core.logging

import org.slf4j.LoggerFactory
import org.slf4j.spi.SLF4JServiceProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
