package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageOwnerProcessTest {
    @Test
    fun `a second process is excluded and an abruptly killed owner releases the OS lock`() = assertProbeOwner("direct")

    @Test
    fun `a retained failed owner survives stack unwind and GC until its process exits`() = assertProbeOwner("retained")

    private fun assertProbeOwner(mode: String) = temporaryRoot { root ->
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ready ->
            ready.soTimeout = 10000
            val classpath = listOf(
                StorageOwnerProbe::class.java,
                DesktopStorageOwner::class.java,
                Unit::class.java,
                Log::class.java,
            ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct().joinToString(java.io.File.pathSeparator)
            val child = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                StorageOwnerProbe::class.java.name,
                root.toString(),
                ready.localPort.toString(),
                mode,
            ).redirectError(root.resolve("probe-error").toFile()).start()
            try {
                ready.accept().use { socket ->
                    socket.soTimeout = 10000
                    assertTrue(DataInputStream(socket.getInputStream()).readBoolean())
                }
                assertNull(DesktopStorageOwner.claim(root))
                child.destroyForcibly()
                child.onExit().get(10, TimeUnit.SECONDS)
                assertNotNull(DesktopStorageOwner.claim(root)).close()
            } finally {
                child.destroyForcibly()
                child.onExit().get(10, TimeUnit.SECONDS)
            }
        }
    }
}

/** Minimal subprocess fixture: signals readiness over a socket rather than relying on sleeps or log parsing. */
object StorageOwnerProbe {
    // This subprocess deliberately exercises GC eligibility of the FileChannel after the owning stack returns.
    @Suppress("ExplicitGarbageCollectionCall")
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments[0])
        if (arguments.getOrNull(2) == "retained") {
            val retained = retainAfterReturning(root)
            System.gc()
            check(retained.get() != null) { "The failed host's lease became collectible" }
            reportReady(arguments[1], true)
            System.`in`.read()
        } else {
            val owner = DesktopStorageOwner.claim(root)
            owner.use {
                reportReady(arguments[1], owner != null)
                System.`in`.read()
            }
        }
    }

    private fun retainAfterReturning(root: Path): WeakReference<DesktopStorageOwner> {
        val owner = checkNotNull(DesktopStorageOwner.claim(root))
        retainDesktopStorageOwnerUntilExit(owner)
        return WeakReference(owner)
    }

    private fun reportReady(port: String, hasOwner: Boolean) {
        Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
            DataOutputStream(socket.getOutputStream()).writeBoolean(hasOwner)
        }
    }
}
