package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.LocalHbNativeContentViewport
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opt-in real SwingPanel/Chromium geometry regression, without Robot, input or screen capture.
 * Run `:features:browser:impl:browserViewportNativeTest` in a desktop session; `jvmTest` skips it.
 * The fixture uses the same asynchronous native panel creation as documents, with a local test page.
 */
class DesktopDocumentViewportNativeTest {
    @Test
    fun `first mount clips native content and viewport changes retain its full document`() {
        assumeTrue(System.getProperty("heartbeat.browser.viewportNativeTest") == "true")
        withNativeViewportFixture { fixture ->
            val state = NativeDocumentViewportState()
            val window = nativeEdt {
                ComposeWindow().apply {
                    title = "Heartbeat document viewport verification (no input)"
                    setSize(800, 760)
                    setContent { NativeDocumentViewportContent(fixture, state) }
                    isVisible = true
                }
            }
            try {
                fixture.loaded.awaitNative()
                fixture.nativeClippingVerified.awaitNative()
                val native = nativeEdt { fixture.panel.createComponent().components.single() as JComponent }
                awaitViewport(native, Rectangle(20, 180, 340, 300)) { }
                assertEquals(Dimension(400, 600), nativeEdt { native.size })
                verifyNativeBounds(fixture, native, window)
                assertEquals(
                    "400x600",
                    fixture.evaluate("window.innerWidth + 'x' + window.innerHeight").jsonPrimitive.content,
                )
                fixture.evaluate("window.retainedMarker = 'viewport-retained'")

                awaitViewport(native, Rectangle(0, 100, 400, 460)) {
                    state.viewport = Rect(100f, 0f, 500f, 460f)
                }
                awaitViewport(native, Rectangle(), isShowing = false) { state.viewport = Rect.Zero }
                awaitViewport(native, Rectangle(40, 200, 280, 240)) {
                    state.viewport = Rect(140f, 100f, 420f, 340f)
                    window.setSize(900, 800)
                }
                assertEquals(Dimension(400, 600), nativeEdt { native.size })
                nativeEdt { native.paintImmediately(0, 0, native.width, native.height) }
                verifyNativeBounds(fixture, native, window)
                assertEquals("viewport-retained", fixture.evaluate("window.retainedMarker").jsonPrimitive.content)
                assertEquals(
                    "400x600",
                    fixture.evaluate("window.innerWidth + 'x' + window.innerHeight").jsonPrimitive.content,
                )
                assertEquals(1, state.factories.get(), "Clipping must not replace SwingPanel")
                assertEquals(1, fixture.pageLoads.get(), "Clipping must not reload the page")
                assertEquals(1, fixture.runtimeStarts.get(), "Clipping must not recreate Chromium")
            } finally {
                nativeEdt { window.dispose() }
            }
        }
    }

    private fun verifyNativeBounds(fixture: NativeComposeBrowserFixture, native: JComponent, window: ComposeWindow) {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val expected = nativeEdt {
            val visible = native.visibleRect
            Rectangle(SwingUtilities.convertPoint(native, visible.location, window.rootPane), visible.size)
        }
        assertEquals(expected, fixture.nativeContentBounds(), "Native UpdateUI must preserve AWT point coordinates")
    }
}

private fun withNativeViewportFixture(block: (NativeComposeBrowserFixture) -> Unit) {
    NativeUncaughtFailures().use {
        NativeComposeBrowserFixture(isViewportProbe = true).use(block)
    }
}

private class NativeDocumentViewportState {
    var viewport by mutableStateOf(Rect(120f, 80f, 460f, 380f))
    val factories = AtomicInteger()
}

@Composable
private fun NativeDocumentViewportContent(fixture: NativeComposeBrowserFixture, state: NativeDocumentViewportState) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density
    val rect = state.viewport
    val host = Rect(
        rect.left * density,
        rect.top * density,
        rect.right * density,
        rect.bottom * density,
    ).translate(origin)
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow(clipBounds = false).topLeft }) {
        CompositionLocalProvider(LocalHbNativeContentViewport provides host) {
            DocumentViewport(Modifier.offset(100.dp, (-100).dp).size(400.dp, 600.dp)) {
                SwingPanel(factory = {
                    state.factories.incrementAndGet()
                    fixture.panel.createComponent()
                })
            }
        }
    }
}

/** Listens to the real native component and ancestor geometry, without timing sleeps or input. */
private fun awaitViewport(component: JComponent, expected: Rectangle, isShowing: Boolean = true, update: () -> Unit) {
    val completed = CompletableFuture<Unit>()
    fun check() {
        if (component.isShowing == isShowing && (!isShowing || component.visibleRect == expected)) {
            completed.complete(Unit)
        }
    }
    val bounds = object : HierarchyBoundsAdapter() {
        override fun ancestorMoved(event: HierarchyEvent) = check()
        override fun ancestorResized(event: HierarchyEvent) = check()
    }
    val visibility = HierarchyListener { check() }
    val own = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = check()
        override fun componentResized(event: ComponentEvent) = check()
        override fun componentShown(event: ComponentEvent) = check()
    }
    nativeEdt {
        component.addHierarchyBoundsListener(bounds)
        component.addHierarchyListener(visibility)
        component.addComponentListener(own)
        update()
        check()
    }
    try {
        completed.awaitNative()
    } finally {
        nativeEdt {
            component.removeHierarchyBoundsListener(bounds)
            component.removeHierarchyListener(visibility)
            component.removeComponentListener(own)
        }
    }
}
