package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
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
}
