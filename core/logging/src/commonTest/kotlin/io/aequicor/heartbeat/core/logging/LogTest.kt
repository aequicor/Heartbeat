package io.aequicor.heartbeat.core.logging

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LogTest {

    private val records = mutableListOf<Record>()
    private val sink = LogSink { level, tag, error, message -> records += Record(level, tag, error, message) }

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun release_filters_below_info_without_building_the_message() {
        Log.init(isDebug = false, sinks = listOf(sink))
        var built = false

        Log.tag("T").d {
            built = true
            "debug"
        }
        Log.tag("T").i { "info" }

        assertFalse(built)
        assertEquals(listOf(Record(LogLevel.INFO, "T", null, "info")), records)
    }

    @Test
    fun debug_stops_below_verbose_and_trace_passes_it_with_the_throwable() {
        Log.init(isDebug = true, sinks = listOf(sink))
        Log.tag("T").v { "filtered trace" }
        assertEquals(listOf<Record>(), records)

        Log.init(isDebug = true, isTrace = true, sinks = listOf(sink))
        val error = IllegalStateException("boom")

        Log.tag("T").v { "trace" }
        Log.tag("T").e(error) { "failed" }

        assertEquals(listOf(LogLevel.VERBOSE, LogLevel.ERROR), records.map { it.level })
        assertSame(error, records.last().error)
    }

    @Test
    fun secrets_are_redacted() {
        Log.init(isDebug = false, sinks = listOf(sink))

        Log.tag("NET").i { "Authorization: Bearer abc.def-123 key=sk-0123456789abcdef mail=user@example.com token=xyz" }

        assertEquals("Authorization: Bearer *** key=sk-*** mail=***@*** token=***", records.single().message)
    }

    @Test
    fun redact_hides_the_value() {
        assertEquals("***", Log.redact("secret"))
        assertEquals("null", Log.redact(null))
    }

    private data class Record(val level: LogLevel, val tag: String, val error: Throwable?, val message: String)
}
