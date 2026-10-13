package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.WindowExceptionHandler
import org.junit.Assume.assumeFalse
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class StudioViewportUiTest {
    @Test
    fun `responsive content uses its parent viewport and local density`() =
        runSkikoComposeUiTest(size = Size(1000f, 700f)) {
            var viewport: DpSize? = null
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Box(Modifier.requiredSize(210.dp, 150.dp)) {
                        StudioViewport { size -> SideEffect { viewport = size } }
                    }
                }
            }
            runOnIdle { assertEquals(DpSize(210.dp, 150.dp), viewport) }
        }

    @Test
    fun `resizing to the docked layout disposes its popup and keeps rendering`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val popupDrawn = AtomicReference(CountDownLatch(1))
        val compactDrawn = AtomicReference(CountDownLatch(1))
        val popupDisposals = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val host = AtomicReference<ComposeWindow>()
        try {
            EventQueue.invokeAndWait {
                val window = ComposeWindow()
                host.set(window)
                window.exceptionHandler = WindowExceptionHandler { error ->
                    failure.compareAndSet(null, error)
                    popupDrawn.get().countDown()
                    compactDrawn.get().countDown()
                }
                window.setSize(1000, 700)
                window.setLocationRelativeTo(null)
                window.setContent {
                    ViewportContent(
                        onPopupDraw = { popupDrawn.get().countDown() },
                        onPopupDispose = { popupDisposals.incrementAndGet() },
                        onCompactDraw = { compactDrawn.get().countDown() },
                    )
                }
                window.isVisible = true
            }
            repeat(3) { cycle ->
                assertTrue(popupDrawn.get().await(15, TimeUnit.SECONDS), "The wide layout must render its popup")
                assertNull(failure.get(), "Opening a popup must not break rendering")
                EventQueue.invokeAndWait {
                    compactDrawn.set(CountDownLatch(1))
                    host.get().setSize(420, 700)
                }
                assertTrue(compactDrawn.get().await(15, TimeUnit.SECONDS), "The compact branch must render")
                assertNull(failure.get(), "Disposing a popup must not leave a disposed render owner")
                assertEquals(cycle + 1, popupDisposals.get(), "The previous layout must release its popup")
                EventQueue.invokeAndWait {
                    popupDrawn.set(CountDownLatch(1))
                    if (cycle < 2) host.get().setSize(1000, 700)
                }
            }
            assertNull(failure.get(), "The final render pass must finish without errors")
        } finally {
            EventQueue.invokeAndWait { host.get()?.dispose() }
        }
    }
}

/** Like StudioWorkspace: switching to the docked width removes a popup's parent. */
@Composable
private fun ViewportContent(onPopupDraw: () -> Unit, onPopupDispose: () -> Unit, onCompactDraw: () -> Unit) {
    StudioViewport(Modifier.fillMaxSize()) { viewport ->
        if (viewport.width > 600.dp) {
            BasicText("Wide workspace")
            Popup {
                val onDisposePopup by rememberUpdatedState(onPopupDispose)
                DisposableEffect(Unit) { onDispose { onDisposePopup() } }
                BasicText(
                    "Conversation tooltip",
                    Modifier.drawWithContent {
                        drawContent()
                        onPopupDraw()
                    },
                )
            }
        } else {
            BasicText(
                "Docked workspace",
                Modifier.drawWithContent {
                    drawContent()
                    onCompactDraw()
                },
            )
        }
    }
}
