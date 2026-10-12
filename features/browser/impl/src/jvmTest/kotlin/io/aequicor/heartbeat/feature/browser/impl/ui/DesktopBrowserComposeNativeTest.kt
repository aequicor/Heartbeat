package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Run alone in a fresh JVM: -Pheartbeat.browser.composeNativeTest=true --tests
 * '*DesktopBrowserComposeNativeTest'. Requires a foreground desktop; macOS needs Accessibility.
 */
class DesktopBrowserComposeNativeTest {
    @Test
    fun `production SwingPanel retains Chromium across Compose layout and dialog focus changes`() {
        assumeTrue(System.getProperty("heartbeat.browser.composeNativeTest") == "true")
        NativeComposeBrowserFixture().use { fixture ->
            val state = NativeBrowserContentState()
            val foreground = CompletableFuture<Unit>()
            val window = nativeEdt {
                ComposeWindow().apply {
                    title = "Heartbeat Compose and JCEF verification"
                    isAlwaysOnTop = true
                    addWindowFocusListener(object : WindowAdapter() {
                        override fun windowGainedFocus(event: WindowEvent) {
                            foreground.complete(Unit)
                        }
                    })
                    setSize(1280, 800)
                    setContent { NativeComposeBrowserContent(fixture, state) }
                    isVisible = true
                    toFront()
                }
            }
            try {
                foreground.awaitNative()
                requireTestForeground(window)
                fixture.loaded.awaitNative()
                val component = nativeEdt { fixture.panel.createComponent() }
                verifyLayout(fixture, state, component)
                verifyThemesAndResize(fixture, state, component, window)
                verifyDialogAndFocus(fixture, state, component, window) { capture(window, "dialog") }
                capture(window, "restored")
                assertEquals(1, fixture.pageLoads.get(), "A Compose transition reloaded the document")
                assertEquals(1, fixture.runtimeStarts.get(), "A Compose transition recreated Chromium")
            } finally {
                nativeEdt { window.dispose() }
            }
        }
    }

    private fun verifyLayout(
        fixture: NativeComposeBrowserFixture,
        state: NativeBrowserContentState,
        component: Component,
    ) {
        fixture.evaluate("window.retainedMarker = 'proof-after-load'")
        val narrow = nativeEdt { component.width }
        assertTrue(narrow > 0, "The native panel must be laid out before changing presentation")
        awaitComponent(component, { width > narrow }) { state.mode = NativeBrowserMode.Expanded }
        val expanded = nativeEdt { component.width }
        assertTrue(expanded > narrow, "Expanded surface must occupy more of the workspace")
        awaitComponent(component, { !isShowing }) { state.mode = NativeBrowserMode.Hidden }
        assertEquals("proof-after-load", fixture.evaluate("window.retainedMarker").jsonPrimitive.content)
        awaitComponent(component, { isShowing && width < expanded }) { state.mode = NativeBrowserMode.Panel }
        assertEquals("proof-after-load", fixture.evaluate("window.retainedMarker").jsonPrimitive.content)
    }

    private fun verifyDialogAndFocus(
        fixture: NativeComposeBrowserFixture,
        state: NativeBrowserContentState,
        component: Component,
        window: ComposeWindow,
        captureDialog: () -> Unit,
    ) {
        val robot = Robot()
        focusPageInput(fixture, component, robot, window)
        armInput(fixture)
        typeKey(robot, KeyEvent.VK_1, window)
        assertEquals("1", fixture.evaluate("window.nextInput").jsonPrimitive.content)
        nativeEdt { state.isDialogVisible = true }
        state.dialogFocused.awaitNative()
        typeKey(robot, KeyEvent.VK_2, window)
        assertEquals("2", state.dialogTyped.awaitNative(), "Native keys must reach the Compose dialog")
        assertEquals("1", fixture.evaluate("document.getElementById('entry').value").jsonPrimitive.content)
        captureDialog()
        nativeEdt { state.isDialogVisible = false }
        state.dialogClosed.awaitNative()
        focusPageInput(fixture, component, robot, window)
        armInput(fixture)
        typeKey(robot, KeyEvent.VK_3, window)
        assertEquals("13", fixture.evaluate("window.nextInput").jsonPrimitive.content)
    }

    private fun verifyThemesAndResize(
        fixture: NativeComposeBrowserFixture,
        state: NativeBrowserContentState,
        component: Component,
        window: ComposeWindow,
    ) {
        val normalWidth = nativeEdt { component.width }
        capture(window, "light-panel")
        awaitComponent(component, { width < normalWidth }) { window.setSize(320, 700) }
        capture(window, "light-narrow")
        nativeEdt { state.isDarkTheme = true }
        state.darkThemeDrawn.awaitNative()
        capture(window, "dark-narrow")
        awaitComponent(component, { width >= normalWidth }) { window.setSize(1280, 800) }
        capture(window, "dark-panel")
        assertEquals("proof-after-load", fixture.evaluate("window.retainedMarker").jsonPrimitive.content)
    }

    private fun armInput(fixture: NativeComposeBrowserFixture) {
        fixture.evaluate(
            "window.nextInput = new Promise(resolve => document.getElementById('entry')" +
                ".addEventListener('input', event => resolve(event.target.value), {once:true})); true",
        )
    }

    private fun focusPageInput(
        fixture: NativeComposeBrowserFixture,
        component: Component,
        robot: Robot,
        window: ComposeWindow,
    ) {
        fixture.evaluate(
            "window.pageFocused = new Promise(resolve => { const entry=document.getElementById('entry');" +
                "const check=()=>{if(document.hasFocus()&&document.activeElement===entry) resolve(true);};" +
                "window.addEventListener('focus', check, {once:true});" +
                "entry.addEventListener('focus', check, {once:true}); check(); }); true",
        )
        val geometry = fixture.evaluate(
            "(() => { const r = document.getElementById('entry').getBoundingClientRect();" +
                "return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth}; })()",
        ).jsonObject
        val point = nativeEdt { component.locationOnScreen }
        val ratio = nativeEdt { component.width } / geometry.getValue("width").jsonPrimitive.double
        requireTestForeground(window, areOwnedDialogsAllowed = false)
        robot.mouseMove(
            point.x + (geometry.getValue("x").jsonPrimitive.double * ratio).toInt(),
            point.y + (geometry.getValue("y").jsonPrimitive.double * ratio).toInt(),
        )
        try {
            requireTestForeground(window, areOwnedDialogsAllowed = false)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        robot.waitForIdle()
        assertTrue(fixture.evaluate("window.pageFocused").jsonPrimitive.content.toBoolean())
    }

    private fun typeKey(robot: Robot, key: Int, window: ComposeWindow) {
        requireTestForeground(window)
        try {
            robot.keyPress(key)
        } finally {
            robot.keyRelease(key)
        }
        robot.waitForIdle()
    }

    private fun awaitComponent(component: Component, predicate: Component.() -> Boolean, change: () -> Unit) {
        val ready = CompletableFuture<Unit>()
        fun inspect() {
            if (component.predicate()) ready.complete(Unit)
        }
        val resizing = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = inspect()
            override fun componentShown(event: ComponentEvent) = inspect()
            override fun componentHidden(event: ComponentEvent) = inspect()
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

    private fun capture(window: ComposeWindow, phase: String) {
        requireTestForeground(window)
        val bounds = nativeEdt { Rectangle(window.locationOnScreen, window.size) }
        val image = Robot().createScreenCapture(bounds)
        requireTestForeground(window)
        val target = File("build/native-ui/compose-native-$phase.png")
        check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
        check(ImageIO.write(image, "png", target))
    }

    /** Never capture or send input when an unrelated application owns the foreground. */
    private fun requireTestForeground(window: ComposeWindow, areOwnedDialogsAllowed: Boolean = true) = nativeEdt {
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val belongsToTest = if (areOwnedDialogsAllowed) {
            generateSequence(active) { it.owner }.any { it === window }
        } else {
            active === window
        }
        check(window.isShowing && belongsToTest && active?.isFocused == true) {
            "Native UI test lost foreground ownership; capture and physical input aborted " +
                "(testShowing=${window.isShowing}, testActive=${window.isActive}, " +
                "testFocused=${window.isFocused}, activeIsTest=${active === window}, " +
                "activeOwnedByTest=$belongsToTest)"
        }
    }
}
