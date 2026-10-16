package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.window.WindowState
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    private val directory = createTempDirectory("hb-instance").resolve("run")
    private val posted = LinkedBlockingQueue<Runnable>()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        directory.parent.deleteRecursively()
    }

    @Test
    fun `a repeated launch hands the foreground over and restores the tray window of the owner`() {
        val owner = assertIs<DesktopInstanceClaim.Owner>(claim()).instance
        val state = WindowState()
        val visibility = DesktopWindowVisibility(state) { error("Activation must not quit") }
        EventQueue.invokeAndWait {
            visibility.installTray { _, _ -> AutoCloseable {} }
            visibility.closeWindow()
            state.isMinimized = true
            owner.onActivate(visibility::openWindow)
        }
        var foregroundHandoffs = 0
        val repeated = claimDesktopInstance(directory, posted::put, { foregroundHandoffs++ })

        assertSame(DesktopInstanceClaim.Forwarded, repeated)
        assertEquals(1, foregroundHandoffs)
        val activation = assertNotNull(posted.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        EventQueue.invokeAndWait {
            activation.run()
            assertTrue(visibility.isVisible)
            assertFalse(state.isMinimized)
            assertEquals(1, visibility.restoreRequest)
            visibility.close()
        }
        owner.close()
    }

    @Test
    fun `an activation that arrives before the window registers is delivered on registration`() {
        val owner = assertIs<DesktopInstanceClaim.Owner>(claim()).instance
        assertSame(DesktopInstanceClaim.Forwarded, claim())
        assertNotNull(posted.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).run()
        var opens = 0

        val registration = owner.onActivate { opens++ }
        registration.close()
        assertSame(DesktopInstanceClaim.Forwarded, claim())
        assertNotNull(posted.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).run()

        assertEquals(1, opens)
        owner.close()
    }

    @Test
    fun `a closed owner frees the instance and a stale socket file does not block the next launch`() {
        val first = assertIs<DesktopInstanceClaim.Owner>(claim()).instance
        first.close()
        Files.createFile(instanceSocket(directory))

        val next = assertIs<DesktopInstanceClaim.Owner>(claim()).instance
        assertSame(DesktopInstanceClaim.Forwarded, claim())
        next.close()

        assertFalse(instanceSocket(directory).exists())
    }

    @Test
    fun `a data directory that cannot be created leaves the launch unguarded`() {
        val blocked = directory.parent.resolve("file")
        Files.createFile(blocked)

        assertSame(DesktopInstanceClaim.Unguarded, claimDesktopInstance(blocked.resolve("run"), posted::put, {}))
    }

    @Test
    fun `the lock lives next to the data each desktop host stores`() {
        assertEquals(
            Path.of("C:/Users/u/AppData/Roaming", "Aequicor", "Heartbeat", "run"),
            desktopInstanceDirectory("Windows 11", "C:/Users/u", "C:/Users/u/AppData/Roaming", null),
        )
        assertEquals(
            Path.of("/Users/u", "Library", "Application Support", "Heartbeat", "run"),
            desktopInstanceDirectory("Mac OS X", "/Users/u", null, null),
        )
        // Absolute on the test host as well: `/data` has no drive on Windows runners.
        val dataHome = directory.parent.resolve("data").toAbsolutePath()
        assertEquals(
            dataHome.resolve("heartbeat").resolve("run"),
            desktopInstanceDirectory("Linux", "/home/u", null, dataHome.toString()),
        )
        assertEquals(
            Path.of("/home/u/.local/share", "heartbeat", "run"),
            desktopInstanceDirectory("Linux", "/home/u", null, "relative"),
        )
    }

    @Test
    fun `a socket path too long for a Unix address moves to a temporary directory`() {
        val socket = instanceSocket(directory.resolve("x".repeat(LONG_SEGMENT)))

        assertTrue(socket.toString().toByteArray().size <= MAX_SOCKET_BYTES)
        assertEquals(socket, instanceSocket(directory.resolve("x".repeat(LONG_SEGMENT))))
    }

    private fun claim() = claimDesktopInstance(directory, posted::put, {}, connectAttempts = 1)

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val LONG_SEGMENT = 120
        const val MAX_SOCKET_BYTES = 100
    }
}
