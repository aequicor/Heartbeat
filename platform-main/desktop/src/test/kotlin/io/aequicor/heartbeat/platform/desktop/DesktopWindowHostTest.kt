package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.launchApplication
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JWindow
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopWindowHostTest {
    @Test
    fun `native activity ends in the tray and reopening retains the same window and content`() = runTest {
        assumeFalse("A desktop display is required for native window visibility", GraphicsEnvironment.isHeadless())
        withContext(Dispatchers.Swing) {
            withTimeout(10.seconds) {
                var isVisible by mutableStateOf(true)
                var activity by mutableStateOf(ComputerUseActivity(isActive = true))
                val native = NativePresentationProbe()
                var contentStarts = 0
                var contentDisposals = 0
                val app = launchApplication {
                    DesktopWindowHost(
                        onCloseRequest = { isVisible = false },
                        state = rememberWindowState(width = 160.dp, height = 120.dp),
                        isVisible = isVisible,
                        nativePresentation = { window -> native.Content(window, activity) },
                    ) {
                        DisposableEffect(Unit) {
                            contentStarts++
                            onDispose { contentDisposals++ }
                        }
                    }
                }
                try {
                    val window = native.attached.await()
                    window.awaitVisibility(true)
                    assertTrue(native.presentations.receive())
                    assertTrue(native.indicators.single().isVisible)

                    isVisible = false
                    window.awaitVisibility(false)
                    assertTrue(window.isDisplayable)
                    activity = ComputerUseActivity()
                    // This must finish without making the hidden window render another frame.
                    assertFalse(native.presentations.receive())
                    assertFalse(native.indicators.single().isVisible)
                    assertFalse(window.isVisible)
                    assertEquals(0, contentDisposals)

                    isVisible = true
                    window.awaitVisibility(true)
                    assertSame(window, native.windows.single())
                    assertEquals(1, contentStarts)
                    isVisible = false
                    window.awaitVisibility(false)
                } finally {
                    withContext(NonCancellable) { app.cancelAndJoin() }
                }
                assertEquals(1, contentDisposals)
                assertEquals(1, native.disposals)
                assertFalse(native.windows.single().isDisplayable)
                assertFalse(native.indicators.single().isDisplayable)
            }
        }
    }

    private class NativePresentationProbe {
        val attached = CompletableDeferred<ComposeWindow>()
        val presentations = Channel<Boolean>(Channel.UNLIMITED)
        val windows = mutableListOf<ComposeWindow>()
        val indicators = mutableListOf<JWindow>()
        var disposals = 0

        @Composable
        fun Content(window: ComposeWindow, activity: ComputerUseActivity) {
            val indicator = remember(window) { JWindow().apply { setSize(16, 16) } }
            DisposableEffect(window) {
                windows += window
                indicators += indicator
                attached.complete(window)
                onDispose {
                    indicator.dispose()
                    disposals++
                }
            }
            DisposableEffect(activity) {
                indicator.isVisible = activity.isActive
                presentations.trySend(activity.isActive)
                onDispose { }
            }
        }
    }
}

/** Observes actual AWT visibility, rather than assuming Compose has applied the requested value. */
private suspend fun Window.awaitVisibility(expected: Boolean) {
    if (isVisible == expected) return
    suspendCancellableCoroutine { continuation ->
        val listener = object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) = completeIfReady()
            override fun componentHidden(event: ComponentEvent) = completeIfReady()

            private fun completeIfReady() {
                if (isVisible == expected && continuation.isActive) {
                    removeComponentListener(this)
                    continuation.resume(Unit)
                }
            }
        }
        addComponentListener(listener)
        continuation.invokeOnCancellation { removeComponentListener(listener) }
    }
}
