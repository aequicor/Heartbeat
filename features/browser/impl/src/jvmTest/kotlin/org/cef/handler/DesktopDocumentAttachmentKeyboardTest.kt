package org.cef.handler

import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.impl.data.ProfileBrowserDocuments
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestDispatchers
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentAttachment
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentDismissal
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentKeyboard
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopDocumentPanel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.cef.browser.CefBrowser
import org.cef.handler.CefKeyboardHandler.CefKeyEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the actual Swing attachment and native key callback without starting Chromium. */
class DesktopDocumentAttachmentKeyboardTest {
    @Test
    fun `native Escape works before and after mounting without making a floating surface modal`() {
        for (bindBeforeMount in listOf(true, false)) {
            verifyEscape(isModal = false, bindBeforeMount = bindBeforeMount)
        }
    }

    @Test
    fun `modal attachments still confine Tab and forward Escape before and after mounting`() {
        for (bindBeforeMount in listOf(true, false)) {
            verifyEscape(isModal = true, bindBeforeMount = bindBeforeMount)
        }
    }

    @Test
    fun `changing modality restores inherited traversal while keeping native Escape available`() = withAttachment {
        var calls = 0
        val callback: () -> Unit = { calls++ }
        it.updateDismissRequest(callback, isModal = true)
        val host = it.createComponent()
        assertTrue(host.isFocusCycleRoot)
        it.updateDismissRequest(callback, isModal = false)
        assertFalse(host.isFocusCycleRoot)
        assertFalse(host.isFocusTraversalPolicySet)
        forwardEscape(it)
        assertEquals(1, calls)
        it.updateDismissRequest(callback, isModal = true)
        assertTrue(host.isFocusCycleRoot)
        forwardEscape(it)
        assertEquals(2, calls)
        it.close()
        assertFalse(host.isFocusCycleRoot)
        assertFalse(host.isFocusTraversalPolicySet)
        assertNull(it.onDismissRequest)
    }

    private fun verifyEscape(isModal: Boolean, bindBeforeMount: Boolean) = withAttachment {
        val mounted = if (bindBeforeMount) null else it.createComponent()
        var calls = 0
        it.updateDismissRequest({ calls++ }, isModal)
        val host = mounted ?: it.createComponent()
        assertEquals(isModal, host.isFocusCycleRoot)
        assertEquals(isModal, host.isFocusTraversalPolicySet)
        forwardEscape(it)
        assertEquals(1, calls)
    }

    private fun forwardEscape(attachment: DesktopDocumentAttachment) {
        val dismissal = DesktopDocumentDismissal { it() }
        dismissal.update(assertNotNull(attachment.onDismissRequest))
        val keyboard = DesktopDocumentKeyboard(dismissal)
        val browser = Proxy.newProxyInstance(
            CefBrowser::class.java.classLoader,
            arrayOf(CefBrowser::class.java),
        ) { _, _, _ -> error("Escape must not access the native browser") } as CefBrowser
        val event = CefKeyEvent(
            CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN,
            0,
            KeyEvent.VK_ESCAPE,
            53,
            false,
            '\u001b',
            '\u001b',
            true,
        )
        assertTrue(keyboard.onKeyEvent(browser, event))
    }

    private fun withAttachment(action: (DesktopDocumentAttachment) -> Unit) = runTest {
        val documents = ProfileBrowserDocuments()
        val handle = assertNotNull(documents.open(BrowserDocument("<input>", "Summary")))
        val panel = DesktopDocumentPanel(
            documents.documents.value.getValue(handle.route.id),
            DesktopBrowserResources { awaitCancellation() },
            BrowserTestDispatchers(StandardTestDispatcher(testScheduler)),
            backgroundScope,
        ) {}
        try {
            SwingUtilities.invokeAndWait {
                val attachment = panel.newAttachment()
                try {
                    action(attachment)
                } finally {
                    attachment.close()
                }
            }
        } finally {
            SwingUtilities.invokeAndWait { panel.release() }
            handle.close()
        }
        panel.awaitClosed()
    }
}
