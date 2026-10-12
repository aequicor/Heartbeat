package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.CompletableFuture
import javax.swing.Timer

/**
 * Content disposal precedes Compose's native window disappearance animation. A layer removed by a later
 * recomposition is disposed without a hidden event, so its showing state is also observed.
 */
internal fun awaitNativeDialogClosed(window: ComposeWindow, dismiss: () -> Unit) {
    val hidden = CompletableFuture<Unit>()
    val children = nativeEdt { window.ownedWindows.filter { it.isShowing } }
    fun inspect() {
        if (children.none { it.isShowing }) hidden.complete(Unit)
    }
    val listener = object : ComponentAdapter() {
        override fun componentHidden(event: ComponentEvent) = inspect()
    }
    val polling = Timer(50) { inspect() }
    nativeEdt {
        children.forEach { it.addComponentListener(listener) }
        polling.start()
    }
    try {
        dismiss()
        nativeEdt { inspect() }
        hidden.awaitNative()
    } finally {
        nativeEdt {
            polling.stop()
            children.forEach { it.removeComponentListener(listener) }
        }
    }
}
