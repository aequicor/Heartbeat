package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.KeyboardFocusManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import javax.swing.Timer

/** Compose focus can be assigned before the popup's AWT peer receives native window focus. */
internal fun awaitNativeOwnedFocus(window: ComposeWindow) {
    val ready = CompletableFuture<Unit>()
    var observation = "No showing owned window"
    val timer = Timer(50) {
        try {
            val popup = window.ownedWindows.singleOrNull { it.isShowing }
            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
            val isPopupFocused = popup != null && focused?.isFocused == true &&
                generateSequence(focused) { it.owner }.any { it === popup }
            val nativeOwnership = window.nativeForegroundOwnership()
            observation = "popupShowing=${popup?.isShowing}, popupFocused=$isPopupFocused, " +
                "testActive=${window.isActive}, testFocused=${window.isFocused}, " +
                "nativeOwnership=$nativeOwnership"
            if (isPopupFocused && nativeOwnership?.permitsInput(true) != false) ready.complete(Unit)
        } catch (failure: Exception) {
            ready.completeExceptionally(failure)
        }
    }
    nativeEdt { timer.start() }
    try {
        ready.awaitNative()
    } catch (failure: TimeoutException) {
        throw AssertionError("The owned popup never received native focus; $observation", failure)
    } finally {
        nativeEdt { timer.stop() }
        File("build/native-ui/owned-window-focus.txt").writeText(observation)
    }
}
