package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
        closing.await()
        assertEquals(listOf("flushed", "destroyed", "exited"), events)
    }

    @Test
    fun `failed cleanup leaves lifecycle and window alive`() = runTest {
        val events = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            shutdownDesktop(
                setOf(AppShutdownParticipant { error("Flush failed") }),
                { events += "destroyed" },
                { events += "exited" },
            )
        }
        assertEquals(emptyList(), events)
    }
}
