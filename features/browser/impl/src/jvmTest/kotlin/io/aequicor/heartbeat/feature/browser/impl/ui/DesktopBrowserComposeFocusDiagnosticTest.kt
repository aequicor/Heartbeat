package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Run alone with -Pheartbeat.browser.composeNativeTest=true --tests '*DesktopBrowserComposeFocusDiagnosticTest'.
 * Records layout and focus events without capturing the desktop or generating physical input.
 */
class DesktopBrowserComposeFocusDiagnosticTest {
    @Test
    fun `records native and AWT ownership through production surface reattachment`() {
        assumeTrue(System.getProperty("heartbeat.browser.composeNativeTest") == "true")
        NativeComposeBrowserFixture().use { fixture ->
            val state = NativeBrowserContentState()
            val foreground = CompletableFuture<Unit>()
            val window = nativeEdt { ComposeWindow() }
            NativeBrowserFocusTrace(window).use { trace ->
                nativeEdt {
                    window.title = "Heartbeat Compose focus diagnostics (no input)"
                    window.isAlwaysOnTop = true
                    window.addWindowFocusListener(object : WindowAdapter() {
                        override fun windowGainedFocus(event: WindowEvent) {
                            foreground.complete(Unit)
                        }
                    })
                    window.setSize(1280, 800)
                    window.setContent { NativeComposeBrowserContent(fixture, state) }
                    window.isVisible = true
                    window.toFront()
                }
                try {
                    foreground.awaitNative()
                    trace.record("initial-focus")
                    fixture.loaded.awaitNative()
                    val component = nativeEdt { fixture.panel.createComponent() }
                    trace.tree("loaded", component)
                    verifyReattachment(fixture, state, component, trace)
                    assertEquals(1, fixture.runtimeStarts.get())
                    assertEquals(1, fixture.pageLoads.get())
                } finally {
                    trace.record("before-dispose")
                    nativeEdt { window.dispose() }
                }
            }
        }
    }

    private fun verifyReattachment(
        fixture: NativeComposeBrowserFixture,
        state: NativeBrowserContentState,
        component: Component,
        trace: NativeBrowserFocusTrace,
    ) {
        fixture.evaluate("window.retainedMarker = 'focus-diagnostic'")
        val narrow = nativeEdt { component.width }
        assertTrue(narrow > 0)
        awaitComponent(component, { width > narrow }) { state.mode = NativeBrowserMode.Expanded }
        trace.tree("expanded", component)
        awaitComponent(component, { !isShowing }) { state.mode = NativeBrowserMode.Hidden }
        trace.tree("hidden", component)
        awaitComponent(component, { isShowing && width <= narrow }) { state.mode = NativeBrowserMode.Panel }
        trace.tree("reattached", component)
        assertEquals("focus-diagnostic", fixture.evaluate("window.retainedMarker").jsonPrimitive.content)
        trace.tree("after-reattached-devtools", component)
    }

    private fun awaitComponent(component: Component, predicate: Component.() -> Boolean, change: () -> Unit) {
        val ready = CompletableFuture<Unit>()
        fun inspect() {
            if (component.predicate()) ready.complete(Unit)
        }
        val resizing = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = inspect()
        }
        val hierarchy = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) inspect()
        }
        nativeEdt {
            component.addComponentListener(resizing)
            component.addHierarchyListener(hierarchy)
            change()
            inspect()
        }
        try {
            ready.awaitNative()
        } finally {
            nativeEdt {
                component.removeComponentListener(resizing)
                component.removeHierarchyListener(hierarchy)
            }
        }
    }
}
