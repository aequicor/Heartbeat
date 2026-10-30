package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v2 waits for the latest geometry, keeps negative monitor coordinates and degrades on launcher failure. */
class DesktopHandoffV2Test {
    private val temp = Files.createTempDirectory("handoff-test")

    @Test
    fun windowOpensOnlyAfterLauncherPermitsPresentation() = runBlocking {
        val dir = temp.resolve("handshake")
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        var received: HandoffPresentation? = null
        val job = launch {
            DesktopHandoff.complete(dir, prepare = {
                received = it
                visibility.openWindow()
            })
        }
        withTimeout(5_000) {
            while (!Files.exists(dir.resolve(DesktopHandoff.READY_FILE))) delay(10)
        }
        assertTrue(Files.exists(dir.resolve(DesktopHandoff.READY_FILE)))
        assertFalse(visibility.isVisible)
        Files.writeString(
            dir.resolve("window.properties"),
            "x=-1200\ny=83\nwidth=1031\nheight=709\ndark=true\nmaximized=false\n",
        )
        Files.writeString(dir.resolve(DesktopHandoff.SHOW_FILE), "v2")
        job.join()
        assertTrue(visibility.isVisible)
        assertEquals(HandoffPresentation(HandoffBounds(-1200, 83, 1031, 709), true, false), received)
        Files.list(dir).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("presented.") }) }
    }

    @Test
    fun timeoutDoesNotBlockStartup() = runBlocking {
        val dir = temp.resolve("timeout")
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        DesktopHandoff.complete(dir, { visibility.openWindow() }, timeoutMillis = 100, pollMillis = 10)
        assertTrue(visibility.isVisible)
    }

    @Test
    fun withoutHandoffDirectoryStartupIsNotHidden() {
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = false) {}
        assertTrue(visibility.isVisible)
    }

    @Test
    fun rejectsInvalidWindowGeometry() {
        Files.writeString(
            temp.resolve("window.properties"),
            "x=1\ny=2\nwidth=-10\nheight=700\ndark=false\nmaximized=false\n",
        )
        assertFailsWith<IllegalArgumentException> { HandoffPresentation.read(temp) }
    }

    @Test
    fun revisionRecordPreservesNormalGeometryAndMinimizedMaximizedState() {
        val presentation = HandoffPresentation(
            HandoffBounds(-1920, 0, 1920, 1040),
            isDark = true,
            isMaximized = true,
            isMinimized = true,
            revision = 27,
            normalBounds = HandoffBounds(-1700, 80, 1100, 720),
            nativeHandle = 123456,
            nativePid = 9123,
            isMoving = true,
        )
        Files.writeString(temp.resolve("window.properties"), presentation.record())
        assertEquals(presentation, HandoffPresentation.read(temp))
    }

    @Test
    fun malformedSnapshotDoesNotPreventOpeningApplication() = runBlocking {
        val directory = temp.resolve("malformed")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("window.properties"), "broken=true")
        Files.writeString(directory.resolve(DesktopHandoff.SHOW_FILE), "v2")
        var isOpened = false
        DesktopHandoff.complete(directory, prepare = {
            assertEquals(null, it)
            isOpened = true
        })
        assertTrue(isOpened)
    }
}
