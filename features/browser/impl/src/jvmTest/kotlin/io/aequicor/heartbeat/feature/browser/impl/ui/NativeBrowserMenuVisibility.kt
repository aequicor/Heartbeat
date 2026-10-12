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
 * the rest of the layer stays transparent over the host background at the window's left eighth. Both must hold on
 * the same captured frame; until then the check keeps polling.
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
                // Both conditions must hold on one frame: an intermediate frame may still paint the whole layer.
                val isDrawn = frame.getRGB(x, y) != background.getRGB(x, y)
                val isOutsideClear = frame.getRGB(outsideX, outsideY) == background.getRGB(outsideX, outsideY)
                observation += ", drawn=$isDrawn, transparentOutside=$isOutsideClear"
                if (isDrawn && isOutsideClear) visible.complete(Unit)
            }
        } catch (failure: Exception) {
            visible.completeExceptionally(failure)
        }
    }
    nativeEdt { timer.start() }
    try {
        visible.awaitNative()
    } catch (failure: TimeoutException) {
        throw AssertionError(
            "The popup must be visible over the native page and its layer transparent outside it; $observation",
            failure,
        )
    } finally {
        nativeEdt { timer.stop() }
        File("build/native-ui/menu-window.txt").writeText(observation)
    }
}
