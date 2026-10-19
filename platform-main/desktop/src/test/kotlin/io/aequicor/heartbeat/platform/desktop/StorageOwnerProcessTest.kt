package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import java.io.DataInputStream
import java.io.DataOutputStream
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
    fun `a second process is excluded and an abruptly killed owner releases the OS lock`() = temporaryRoot { root ->
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
    @JvmStatic
    fun main(arguments: Array<String>) {
        val owner = DesktopStorageOwner.claim(Path.of(arguments[0]))
        owner.use {
            Socket(InetAddress.getLoopbackAddress(), arguments[1].toInt()).use { socket ->
                DataOutputStream(socket.getOutputStream()).writeBoolean(owner != null)
            }
            System.`in`.read()
        }
    }
}
