package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopWindowFailuresTest {
    @Test
    fun `frame failure is persisted before delegation without exposing rendered content`() {
        val records = mutableListOf<Throwable?>()
        val error = IndexOutOfBoundsException("private rendered content")
        val sink = LogSink { level, tag, failure, message ->
            assertEquals(LogLevel.ERROR, level)
            assertEquals("DesktopWindow", tag)
            assertEquals("Unhandled window failure", message)
            records += failure
        }
        Log.init(isDebug = false, sinks = listOf(sink))
        try {
            val handler = loggedWindowExceptionHandler(
                WindowExceptionHandler { original ->
                    assertEquals(1, records.size, "Persist the failure before a blocking error dialog opens")
                    assertSame(error, original)
                    throw original
                },
            )

            assertSame(error, assertFailsWith<IndexOutOfBoundsException> { handler.onException(error) })
            val persisted = assertNotNull(records.single())
            assertTrue(persisted.toString().contains("IndexOutOfBoundsException"))
            assertTrue(!persisted.toString().contains("private rendered content"))
            assertEquals(error.stackTrace.toList(), persisted.stackTrace.toList())
        } finally {
            Log.init(isDebug = false)
        }
    }
}
