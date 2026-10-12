package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.CompletableFuture

/** Content disposal precedes Compose's native window disappearance animation. */
internal fun awaitNativeDialogClosed(window: ComposeWindow, dismiss: () -> Unit) {
    val hidden = CompletableFuture<Unit>()
    val children = nativeEdt { window.ownedWindows.filter { it.isShowing } }
    fun inspect() {
        if (children.none { it.isShowing }) hidden.complete(Unit)
    }
    val listener = object : ComponentAdapter() {
        override fun componentHidden(event: ComponentEvent) = inspect()
    }
    nativeEdt { children.forEach { it.addComponentListener(listener) } }
    try {
        dismiss()
        nativeEdt { inspect() }
        hidden.awaitNative()
    } finally {
        nativeEdt { children.forEach { it.removeComponentListener(listener) } }
    }
}
