package io.aequicor.heartbeat.platform.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
}
