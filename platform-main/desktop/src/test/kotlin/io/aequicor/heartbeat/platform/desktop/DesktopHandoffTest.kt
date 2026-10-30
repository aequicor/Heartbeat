package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Рукопожатие handoff: ready → hidden → окно открывается; таймаут не блокирует запуск. */
class DesktopHandoffTest {
    private val temp = Files.createTempDirectory("handoff-test")

    @Test
    fun windowOpensOnlyAfterHidden() = runBlocking {
        val dir = temp.resolve("handshake")
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        val job = launch { DesktopHandoff.complete(dir, visibility) }
        withTimeout(5_000) {
            while (!Files.exists(dir.resolve(DesktopHandoff.READY_FILE))) delay(10)
        }
        assertTrue(Files.exists(dir.resolve(DesktopHandoff.READY_FILE)))
        assertFalse(visibility.isVisible)
        Files.writeString(dir.resolve(DesktopHandoff.HIDDEN_FILE), "hidden")
        job.join()
        assertTrue(visibility.isVisible)
    }

    @Test
    fun timeoutDoesNotBlockStartup() = runBlocking {
        val dir = temp.resolve("timeout")
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        DesktopHandoff.complete(dir, visibility, timeoutMillis = 100, pollMillis = 10)
        assertTrue(visibility.isVisible)
    }

    @Test
    fun withoutHandoffDirectoryStartupIsNotHidden() {
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = false) {}
        assertTrue(visibility.isVisible)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun mainWindowCanReuseTheIntroductionHandoff() = runTest {
        val dir = temp.resolve("introduction")
        val introduction = launch { DesktopHandoff.awaitHidden(dir) }
        runCurrent()
        assertTrue(Files.exists(dir.resolve(DesktopHandoff.READY_FILE)))
        assertTrue(introduction.isActive)
        Files.writeString(dir.resolve(DesktopHandoff.HIDDEN_FILE), "hidden")
        advanceUntilIdle()
        assertTrue(introduction.isCompleted)
        DesktopHandoff.requestActivation(dir)
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        DesktopHandoff.complete(dir, visibility, timeoutMillis = 0)
        assertTrue(visibility.isVisible)
        assertEquals(1, visibility.restoreRequest)
    }

    @Test
    fun backgroundHandoffPreservesMinimizationAndGeometryWithoutActivation() = runTest {
        val dir = temp.resolve("background")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(DesktopHandoff.HIDDEN_FILE), "hidden")
        Files.writeString(
            dir.resolve("presentation.properties"),
            "activate=false\nminimized=true\nx=20\ny=-200\nwidth=1000\nheight=700\nmaximized=true\n",
        )
        val state = WindowState()
        val visibility = DesktopWindowVisibility(state, startHidden = true) {}
        DesktopHandoff.complete(dir, visibility)
        assertTrue(visibility.isVisible)
        assertTrue(state.isMinimized)
        assertEquals(WindowPosition(20.dp, (-200).dp), state.position)
        assertEquals(DpSize(1000.dp, 700.dp), state.size)
        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(0, visibility.restoreRequest)
        visibility.openWindow()
        assertFalse(state.isMinimized)
        assertEquals(1, visibility.restoreRequest)
    }

    @Test
    fun oversizedOrInvalidNativeMetadataDoesNotRequestFocusOrResize() {
        val dir = temp.resolve("invalid-presentation")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(DesktopHandoff.HIDDEN_FILE), "hidden")
        val file = dir.resolve("presentation.properties")
        Files.writeString(file, "activate=yes\nwidth=-1\nheight=700\nx=0\ny=0")
        val invalid = DesktopHandoff.presentation(dir)
        assertFalse(invalid.isActivationRequested)
        assertNull(invalid.bounds)
        Files.writeString(file, "activate=true\n" + "x".repeat(2048))
        assertFalse(DesktopHandoff.presentation(dir).isActivationRequested)
        Files.writeString(file, "activate=false\nminimized=true")
        DesktopHandoff.requestActivation(dir)
        val continued = DesktopHandoff.presentation(dir)
        assertTrue(continued.isActivationRequested)
        assertFalse(continued.isMinimized)
    }

    @Test
    fun metadataCannotActivateUntilNativeWindowIsHidden() {
        val dir = temp.resolve("unacknowledged")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("presentation.properties"), "activate=true")
        assertFalse(DesktopHandoff.presentation(dir).isActivationRequested)
        Files.writeString(dir.resolve(DesktopHandoff.HIDDEN_FILE), "hidden")
        assertTrue(DesktopHandoff.presentation(dir).isActivationRequested)
    }

    @Test
    fun cancelledStartupNeverShowsTheJvmWindow() = runTest {
        val dir = temp.resolve("cancelled")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("cancel"), "cancel")
        val visibility = DesktopWindowVisibility(WindowState(), startHidden = true) {}
        assertFailsWith<CancellationException> { DesktopHandoff.complete(dir, visibility) }
        assertFalse(visibility.isVisible)
        assertFalse(Files.exists(dir.resolve(DesktopHandoff.READY_FILE)))
    }
}
