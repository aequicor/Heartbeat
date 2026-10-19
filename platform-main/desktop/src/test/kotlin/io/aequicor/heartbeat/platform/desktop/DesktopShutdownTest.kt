package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.awt.desktop.QuitResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopShutdownTest {
    @Test
    fun `worker closes admission after capturing child identities and before native cleanup`() = runTest {
        val events = mutableListOf<String>()
        val result = shutdownDesktop(
            setOf(AppShutdownParticipant { events += "native" }),
            destroy = {},
            exit = {},
            stopAdmission = { events += "admission" },
            stopScopes = { events += "scopes" },
            processes = object : DesktopProcessCleanup {
                override suspend fun capture() {
                    events += "capture"
                }
                override suspend fun stop() {
                    events += "processes"
                }
            },
        )
        assertNull(result)
        assertEquals(listOf("capture", "admission", "native", "scopes", "processes"), events)
    }

    @Test
    fun `cancelled scope closure still stops children before propagating cancellation`() = runTest {
        val events = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            shutdownDesktop(
                emptySet(),
                { events += "destroyed" },
                { events += "exited" },
                stopScopes = { throw CancellationException("scope closed") },
                processes = object : DesktopProcessCleanup {
                    override suspend fun capture() = Unit
                    override suspend fun stop() {
                        events += "stopped"
                    }
                },
            )
        }
        assertEquals(listOf("destroyed", "stopped", "exited"), events)
    }

    @Test
    fun `noncancellable native cleanup cannot hold quit past its deadline`() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val closing = async {
            shutdownDesktop(
                setOf(AppShutdownParticipant { withContext(NonCancellable) { release.await() } }),
                { events += "destroyed" },
                { events += "exited" },
                timeoutMillis = 100,
            )
        }
        assertIs<IllegalStateException>(closing.await())
        assertEquals(listOf("destroyed", "exited"), events)
        release.complete(Unit)
        runCurrent()
    }

    @Test
    fun `quit captures children before owners close and awaits all cleanup before exit`() = runTest {
        val events = mutableListOf<String>()
        val stopped = CompletableDeferred<Unit>()
        val processes = object : DesktopProcessCleanup {
            override suspend fun capture() {
                events += "captured"
            }
            override suspend fun stop() {
                events += "stopping"
                stopped.await()
                events += "stopped"
            }
        }
        val closing = async {
            shutdownDesktop(
                setOf(AppShutdownParticipant { events += "native" }),
                { events += "destroyed" },
                { events += "exited" },
                stopScopes = { events += "scopes" },
                processes = processes,
            )
        }
        runCurrent()
        assertEquals(listOf("captured", "native", "scopes", "destroyed", "stopping"), events)
        assertFalse(closing.isCompleted)
        stopped.complete(Unit)
        assertNull(closing.await())
        assertEquals(listOf("captured", "native", "scopes", "destroyed", "stopping", "stopped", "exited"), events)
    }

    @Test
    fun `scope timeout still destroys UI and stops children before exit`() = runTest {
        val events = mutableListOf<String>()
        val failure = shutdownDesktop(
            emptySet(),
            { events += "destroyed" },
            { events += "exited" },
            timeoutMillis = 100,
            stopScopes = { awaitCancellation() },
            processes = object : DesktopProcessCleanup {
                override suspend fun capture() {
                    events += "captured"
                }
                override suspend fun stop() {
                    events += "stopped"
                }
            },
        )
        assertIs<IllegalStateException>(failure)
        assertEquals(listOf("captured", "destroyed", "stopped", "exited"), events)
    }

    @Test
    fun `failed UI disposal and child cleanup are reported without preventing exit`() = runTest {
        val events = mutableListOf<String>()
        val failure = shutdownDesktop(
            emptySet(),
            { error("UI failure") },
            { events += "exited" },
            processes = object : DesktopProcessCleanup {
                override suspend fun capture() = Unit
                override suspend fun stop() {
                    events += "stopped"
                    error("Child failure")
                }
            },
        )
        assertEquals("UI failure", failure?.message)
        assertEquals("Child failure", failure?.suppressed?.single()?.message)
        assertEquals(listOf("stopped", "exited"), events)
    }

    @Test
    fun `restart closes scopes while preserving the detached updater`() = runTest {
        val events = mutableListOf<String>()
        assertNull(
            shutdownDesktop(
                setOf(AppShutdownParticipant { events += "native" }),
                { events += "destroyed" },
                { events += "exited" },
                stopScopes = { events += "scopes" },
            ),
        )
        assertEquals(listOf("native", "scopes", "destroyed", "exited"), events)
    }

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

    @Test
    fun `failing lifecycle destruction still exits the process`() = runTest {
        val events = mutableListOf<String>()
        val failure = shutdownDesktop(
            emptySet(),
            { error("destroy failed") },
            { events += "exited" },
        )
        assertIs<IllegalStateException>(failure)
        assertEquals(listOf("exited"), events)
    }

    @Test
    fun `system quit is answered once after exit and never cancelled`() {
        val answers = mutableListOf<String>()
        val response = object : QuitResponse {
            override fun performQuit() {
                answers += "perform"
            }

            override fun cancelQuit() {
                answers += "cancel"
            }
        }
        val quit = DesktopSystemQuit()
        quit.complete()
        quit.defer(response)
        quit.complete()
        quit.complete()
        assertEquals(listOf("perform"), answers)
    }
}
