package io.aequicor.heartbeat.platform.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProcessWorkerCommandTest {
    @Test
    fun `CLI accepts only explicit operations and safe profile identifiers`() {
        assertEquals(ProcessWorkerCommand("start"), parseWorkerCommand(listOf("start")))
        assertEquals(ProcessWorkerCommand("start", "local"), parseWorkerCommand(listOf("start", "--profile", "local")))
        listOf(
            emptyList(),
            listOf("start", "--profile", "../other"),
            listOf("stop", "--profile", "local"),
            listOf("restart"),
            listOf("start", "--trust", "full"),
        ).forEach { assertFailsWith<IllegalArgumentException> { parseWorkerCommand(it) } }
    }

    @Test
    fun `a persisted intervention survives replacement of the worker without reading profile stores`() =
        temporaryRoot { root ->
            DesktopStorageOwner.claim(root)!!.use { owner ->
                writeWorkerWait(owner.directory, WorkerProblem.InteractionRequired)
            }
            DesktopStorageOwner.claim(root)!!.use { next ->
                assertEquals(WorkerProblem.InteractionRequired, readWorkerWait(next.directory))
            }
        }

    @Test
    fun `UI attaches only a monitor when worker owns storage and never calls the graph factory`() =
        temporaryRoot { root ->
            DesktopStorageOwner.claim(root)!!.use { owner ->
                WorkerControl(owner, true, { WorkerSnapshot(WorkerPhase.Running) }, {}).use {
                    var launched = false
                    var monitored = false
                    withDesktopStorageOwner(root, { monitored = true }) { launched = true }
                    assertFalse(launched)
                    assertEquals(true, monitored)
                    assertEquals(WorkerPhase.Running, workerRequest(root, "status")?.phase)
                }
            }
        }

    @Test
    fun `explicit UI startup clears a pause only after claiming storage and does not answer decisions`() =
        temporaryRoot { root ->
            DesktopStorageOwner.claim(root)!!.use { writeWorkerWait(it.directory, WorkerProblem.InteractionRequired) }
            withDesktopStorageOwner(root, { error("Unexpected monitor") }) {
                assertFalse(Files.exists(root.resolve("execution-owner/waiting")))
            }
        }

    @Test
    fun `failed UI launch retains the OS lease after its stack unwinds`() = temporaryRoot { root ->
        val failure = IllegalStateException("Root failed after starting profile services")
        var retained: DesktopStorageOwner? = null
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                withDesktopStorageOwner(
                    root,
                    monitor = { error("Unexpected monitor") },
                    retainUntilExit = { retained = it },
                ) {
                    assertNull(DesktopStorageOwner.claim(root))
                    throw failure
                }
            }
            assertSame(failure, thrown)
            assertNotNull(retained)
            assertNull(DesktopStorageOwner.claim(root))
        } finally {
            retained?.close()
        }
        assertNotNull(DesktopStorageOwner.claim(root)).close()
    }

    @Test
    fun `normally completed UI launch releases storage without retaining a failed lease`() = temporaryRoot { root ->
        withDesktopStorageOwner(
            root,
            monitor = { error("Unexpected monitor") },
            retainUntilExit = { error("Successful launch must release normally") },
        ) {
            withFailedDesktopLaunchCleanup(cleanup = { error("Successful launch needs no failure cleanup") }) {
                assertNull(DesktopStorageOwner.claim(root))
            }
        }
        assertNotNull(DesktopStorageOwner.claim(root)).close()
    }

    @Test
    fun `failed cleanup preserves the launch error and never opens an ownership gap`() = temporaryRoot { root ->
        val events = mutableListOf<String>()
        val failure = IllegalStateException("Window initialization failed")
        var retained: DesktopStorageOwner? = null
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                withDesktopStorageOwner(root, retainUntilExit = {
                    events += "retain"
                    retained = it
                }) {
                    withFailedDesktopLaunchCleanup(cleanup = {
                        assertNull(DesktopStorageOwner.claim(root))
                        events += "cleanup"
                        throw java.io.IOException("Native shutdown failed")
                    }) {
                        events += "launch"
                        throw failure
                    }
                }
            }
            assertSame(failure, thrown)
            assertEquals(listOf("launch", "cleanup", "retain"), events)
            assertNull(DesktopStorageOwner.claim(root))
        } finally {
            retained?.close()
        }
    }

    @Test
    fun `fatal launch errors retain exclusion even when they bypass ordinary exception handlers`() =
        temporaryRoot { root ->
            var retained: DesktopStorageOwner? = null
            try {
                assertFailsWith<LinkageError> {
                    withDesktopStorageOwner(root, retainUntilExit = { retained = it }) {
                        throw LinkageError("Native renderer unavailable")
                    }
                }
                assertNotNull(retained)
                assertNull(DesktopStorageOwner.claim(root))
            } finally {
                retained?.close()
            }
        }
}
