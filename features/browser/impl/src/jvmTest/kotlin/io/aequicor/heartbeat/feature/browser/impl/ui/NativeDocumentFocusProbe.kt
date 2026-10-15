package io.aequicor.heartbeat.feature.browser.impl.ui

import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentEngine
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.cef.browser.CefBrowser
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleState
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises Chromium's actual Tab boundary through CDP; no desktop input injection or screen capture. */
internal fun verifyNativeDocumentCloseFocus(
    window: Window,
    panel: DesktopDocumentPanel,
    closeLabel: String,
    isBackward: Boolean,
) {
    val browser = nativeEdt { documentBrowser(panel) }
    nativeEdt {
        browser.uiComponent.requestFocusInWindow()
        browser.setFocus(true)
    }
    val client = assertNotNull(nativeEdt { browser.devToolsClient })
    val focused = client.executeDevToolsMethod(
        "Runtime.evaluate",
        buildJsonObject {
            put("expression", "document.getElementById('entry').focus(); document.activeElement.id")
            put("returnByValue", true)
        }.toString(),
    ).awaitNative()
    assertEquals(
        JsonPrimitive("entry"),
        Json.parseToJsonElement(focused).jsonObject["result"]?.jsonObject?.get("value"),
    )
    for (type in listOf("keyDown", "keyUp")) {
        client.executeDevToolsMethod(
            "Input.dispatchKeyEvent",
            buildJsonObject {
                put("type", type)
                put("key", "Tab")
                put("code", "Tab")
                put("windowsVirtualKeyCode", KeyEvent.VK_TAB)
                put("nativeVirtualKeyCode", KeyEvent.VK_TAB)
                put("modifiers", if (isBackward) 8 else 0)
            }.toString(),
        ).awaitNative()
    }
    awaitNativeCloseFocus(window, panel, closeLabel)
    nativeEdt {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val target = assertNotNull(focusManager.focusOwner)
        assertTrue(
            !SwingUtilities.isDescendingFrom(target, panel.createComponent()),
            "Native focus never left the page",
        )
        val key = if (isBackward) KeyEvent.VK_SPACE else KeyEvent.VK_ENTER
        val character = if (isBackward) ' ' else '\n'
        // Deliver to the actual AWT focus owner inside this test window, without the operating system input queue.
        for (type in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            focusManager.redispatchEvent(target, KeyEvent(target, type, System.currentTimeMillis(), 0, key, character))
        }
    }
}

private fun awaitNativeCloseFocus(window: Window, panel: DesktopDocumentPanel, label: String) {
    val focused = CompletableFuture<Unit>()
    val timer = nativeEdt {
        Timer(20) {
            try {
                val controls = accessibleContexts(window.accessibleContext).filter { it.accessibleName == label }
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                val isCloseFocused =
                    controls.singleOrNull()?.accessibleStateSet?.contains(AccessibleState.FOCUSED) == true
                val isOutsidePage = owner != null && !SwingUtilities.isDescendingFrom(owner, panel.createComponent())
                if (isCloseFocused && isOutsidePage) {
                    focused.complete(Unit)
                }
            } catch (error: Throwable) {
                focused.completeExceptionally(error)
            }
        }.apply { start() }
    }
    try {
        focused.awaitNative()
    } finally {
        nativeEdt(timer::stop)
    }
}

private fun accessibleContexts(context: AccessibleContext): List<AccessibleContext> = buildList {
    add(context)
    repeat(context.accessibleChildrenCount) { index ->
        context.getAccessibleChild(index)?.accessibleContext?.let { addAll(accessibleContexts(it)) }
    }
}

/** Test-only access avoids adding native-browser or automation handles to production document APIs. */
private fun documentBrowser(panel: DesktopDocumentPanel): CefBrowser {
    val engine = DesktopDocumentPanel::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(panel)
    return DesktopDocumentEngine::class.java.getDeclaredField("browser").apply { isAccessible = true }
        .get(assertNotNull(engine)) as CefBrowser
}
