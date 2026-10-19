package io.aequicor.heartbeat.platform.desktop

import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStorageOwnerTest {
    @Test
    fun `worker and UI cannot concurrently own canonical aliases`() = temporaryRoot { root ->
        val alias = if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
            root.resolve("alias").also { Files.createSymbolicLink(it, root) }
        } else {
            root.resolve(".")
        }
        assertNotNull(DesktopStorageOwner.claim(root)).use {
            assertNull(DesktopStorageOwner.claim(alias))
        }
        assertNotNull(DesktopStorageOwner.claim(alias)).close()
    }

    @Test
    fun `stale control metadata does not prevent a fresh owner`() = temporaryRoot { root ->
        assertNotNull(DesktopStorageOwner.claim(root)).close()
        Files.writeString(root.resolve("execution-owner/control"), "stale")
        assertNotNull(DesktopStorageOwner.claim(root)).use { owner ->
            WorkerControl(owner, true, { WorkerSnapshot(WorkerPhase.Recovering) }, {}).use {
                assertEquals(WorkerPhase.Recovering, workerRequest(root, "status")?.phase)
            }
        }
    }

    @Test
    fun `status and stop use only control plane while storage stays excluded`() = temporaryRoot { root ->
        val stopped = CountDownLatch(1)
        assertNotNull(DesktopStorageOwner.claim(root)).use { owner ->
            WorkerControl(owner, true, { WorkerSnapshot(WorkerPhase.Waiting, decisions = 2) }, stopped::countDown).use {
                assertEquals(2, workerRequest(root, "status")?.decisions)
                assertEquals(WorkerPhase.Stopping, workerRequest(root, "stop")?.phase)
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                assertNull(DesktopStorageOwner.claim(root))
            }
        }
        assertNotNull(DesktopStorageOwner.claim(root)).close()
    }

    @Test
    fun `UI owner is never stopped by worker command`() = temporaryRoot { root ->
        assertNotNull(DesktopStorageOwner.claim(root)).use { owner ->
            WorkerControl(owner, false, { error("No worker snapshot") }, { error("Cannot stop UI") }).use {
                assertNull(workerRequest(root, "stop"))
            }
        }
    }

    @Test
    fun `invalid token cannot stop worker and oversized frame cannot exhaust its parser`() = temporaryRoot { root ->
        val stopped = CountDownLatch(1)
        assertNotNull(DesktopStorageOwner.claim(root)).use { owner ->
            WorkerControl(owner, true, { WorkerSnapshot(WorkerPhase.Running) }, stopped::countDown).use {
                val metadata = Files.readAllLines(owner.directory.resolve("control"))
                Files.writeString(owner.directory.resolve("control"), "1\n${metadata[1]}\n${"0".repeat(64)}\n")
                assertFailsWith<IllegalStateException> { workerRequest(root, "stop") }
                Files.writeString(owner.directory.resolve("control"), metadata.joinToString("\n", postfix = "\n"))
                Socket("localhost", metadata[1].toInt()).use { socket ->
                    java.io.DataOutputStream(socket.getOutputStream()).writeInt(Int.MAX_VALUE)
                }
                assertEquals(WorkerPhase.Running, workerRequest(root, "status")?.phase)
                assertEquals(1L, stopped.count)
            }
        }
    }
}

internal fun temporaryRoot(test: (Path) -> Unit) {
    val root = Files.createTempDirectory("heartbeat-owner-test")
    try {
        test(root)
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
