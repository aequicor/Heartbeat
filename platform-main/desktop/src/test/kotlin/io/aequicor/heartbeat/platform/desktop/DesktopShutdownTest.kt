package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopShutdownTest {
    @Test
    fun `native completion precedes lifecycle destruction and process exit`() = runTest {
        val callback = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val closing = async {
            shutdownDesktop(
                setOf(
                    AppShutdownParticipant {
                        callback.await()
                        events += "flushed"
                    },
                ),
                { events += "destroyed" },
                { events += "exited" },
            )
        }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(emptyList(), events)
        callback.complete(Unit)
        assertNull(closing.await())
        assertEquals(listOf("flushed", "destroyed", "exited"), events)
    }

    @Test
    fun `failed cleanup is reported and the app still exits after other participants finish`() = runTest {
        val events = mutableListOf<String>()
        val callback = CompletableDeferred<Unit>()
        val closing = async {
            shutdownDesktop(
                setOf(
                    AppShutdownParticipant { error("Flush failed") },
                    AppShutdownParticipant {
                        callback.await()
                        events += "flushed"
                    },
                    AppShutdownParticipant { throw IllegalArgumentException("Second failure") },
                ),
                { events += "destroyed" },
                { events += "exited" },
            )
        }
        runCurrent()
        assertEquals(emptyList(), events)
        callback.complete(Unit)
        val failure = closing.await()
        assertIs<IllegalStateException>(failure)
        assertEquals("Flush failed", failure.message)
        assertIs<IllegalArgumentException>(failure.suppressed.single())
        assertEquals(listOf("flushed", "destroyed", "exited"), events)
    }

    @Test
    fun `stuck participant is abandoned after the bound and the app exits`() = runTest {
        val events = mutableListOf<String>()
        val failure = shutdownDesktop(
            setOf(AppShutdownParticipant { awaitCancellation() }),
            { events += "destroyed" },
            { events += "exited" },
            timeoutMillis = 1_000,
        )
        assertIs<IllegalStateException>(failure)
        assertTrue(failure.message.orEmpty().contains("1000 ms"))
        assertEquals(listOf("destroyed", "exited"), events)
    }
}
