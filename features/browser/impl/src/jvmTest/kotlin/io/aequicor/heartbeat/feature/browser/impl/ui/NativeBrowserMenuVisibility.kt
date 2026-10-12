package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.KeyboardFocusManager
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import javax.swing.Timer

/** A Compose draw callback precedes the native compositor presenting its first popup frame. */
internal fun awaitNativeMenuVisible(window: ComposeWindow, background: BufferedImage, capture: () -> BufferedImage) {
    val visible = CompletableFuture<Unit>()
    var observation = "No showing popup window"
    val timer = Timer(50) {
        try {
            val popup = window.ownedWindows.singleOrNull { it.isShowing }
            if (popup != null) {
                val origin = window.locationOnScreen
                val x = popup.x - origin.x + popup.width / 2
                val y = popup.y - origin.y + popup.height / 2
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                observation = "bounds=${popup.bounds}, focused=${focused === popup}, " +
                    "showing=${popup.isShowing}, center=($x,$y)"
                val frame = capture()
                check(x in 0 until frame.width && y in 0 until frame.height) { observation }
                if (frame.getRGB(x, y) != background.getRGB(x, y)) visible.complete(Unit)
            }
        } catch (failure: Exception) {
            visible.completeExceptionally(failure)
        }
    }
    nativeEdt { timer.start() }
    try {
        visible.awaitNative()
    } catch (failure: TimeoutException) {
        throw AssertionError("The menu must be visible over the native page; $observation", failure)
    } finally {
        nativeEdt { timer.stop() }
        File("build/native-ui/menu-window.txt").writeText(observation)
    }
}
