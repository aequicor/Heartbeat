package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.KeyboardFocusManager
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import javax.swing.Timer

/**
 * A Compose draw callback precedes the native compositor presenting its first popup frame. Checks the pixel at
 * the centre of the drawn popup surface (not of its layer window, which may cover the whole client area) and that
 * the rest of the layer stays transparent over the host background at the window's left eighth.
 */
internal fun awaitNativeMenuVisible(
    window: ComposeWindow,
    state: NativeBrowserContentState,
    background: BufferedImage,
    capture: () -> BufferedImage,
) {
    val visible = CompletableFuture<Unit>()
    var observation = "No showing popup window"
    val timer = Timer(50) {
        try {
            val popup = window.ownedWindows.singleOrNull { it.isShowing }
            val surface = state.popupBounds
            if (popup != null && surface != null) {
                val scale = window.graphicsConfiguration.defaultTransform.scaleX
                val origin = window.locationOnScreen
                val container = window.contentPane.locationOnScreen
                val x = container.x - origin.x + (surface.center.x / scale).toInt()
                val y = container.y - origin.y + (surface.center.y / scale).toInt()
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                observation = "layer=${popup.bounds}, surface=$surface, scale=$scale, focused=${focused === popup}, " +
                    "center=($x,$y), type=${popup.type}, alwaysOnTop=${popup.isAlwaysOnTop}, " +
                    "background=${popup.background}, opacity=${popup.opacity}, ownerType=${window.type}, " +
                    "ownerAlwaysOnTop=${window.isAlwaysOnTop}, renderer=${window.renderApi}"
                val frame = capture()
                check(x in 0 until frame.width && y in 0 until frame.height) { observation }
                val outsideX = frame.width / 8
                val outsideY = frame.height / 2
                if (frame.getRGB(x, y) != background.getRGB(x, y)) {
                    check(frame.getRGB(outsideX, outsideY) == background.getRGB(outsideX, outsideY)) {
                        "The popup layer must stay transparent outside its surface; $observation"
                    }
                    visible.complete(Unit)
                }
            }
        } catch (failure: Exception) {
            visible.completeExceptionally(failure)
        }
    }
    nativeEdt { timer.start() }
    try {
        visible.awaitNative()
    } catch (failure: TimeoutException) {
        throw AssertionError("The popup must be visible over the native page; $observation", failure)
    } finally {
        nativeEdt { timer.stop() }
        File("build/native-ui/menu-window.txt").writeText(observation)
    }
}
