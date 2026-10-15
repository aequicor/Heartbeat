package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.coroutines.CancellationException
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Compatibility for the pinned JCEF content view on macOS 14+, where NSView no longer clips by default.
 * Owned by one document engine. Requests and closure run on EDT; each attempt captures the current owning
 * window anew, finishes its AppKit work before returning, and retains no native view pointers.
 * A short event-driven burst covers JCEF's asynchronous parent attachment, including additional browsers
 * joining a window which already contains clipped siblings. Hidden documents wait for their next showing event.
 * Missing native views are expected during startup; attachment is required only after the document has loaded.
 * JCEF restarts its own delayed parent attachment on every paint. A trailing paint notification therefore
 * rearms the burst even after creation/load retries found only an already attached sibling in the window.
 */
internal class MacDocumentNativeClipping(
    private val component: JComponent,
    private val onFailure: () -> Unit,
    private val access: MacDocumentClipAccess = CocoaDocumentClipAccess(),
    private val retries: DocumentClipRetries = SwingDocumentClipRetries(),
    private val onClipped: () -> Unit = {},
) : AutoCloseable {
    private val log = Log.tag("MacDocumentNativeClipping")
    private var generation = 0L
    private var isClosed = false
    private var hasLoaded = false
    private var pending: AutoCloseable? = null
    private val hierarchy = HierarchyListener { event ->
        if (event.changeFlags and (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.PARENT_CHANGED).toLong() != 0L) {
            request()
        }
    }

    init {
        check(SwingUtilities.isEventDispatchThread())
        component.addHierarchyListener(hierarchy)
    }

    /** Try clipping after native creation or attachment, without treating a slow initial load as failure. */
    fun request() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        pending?.close()
        pending = null
        val current = ++generation
        if (isNativeVisible()) attempt(current, 0)
    }

    /** Enable attachment validation after the first document load; hidden documents still wait until shown. */
    fun documentLoaded() {
        check(SwingUtilities.isEventDispatchThread())
        hasLoaded = true
        request()
    }

    /**
     * Called after JCEF's child paint on EDT. Native work waits until painting settles, beyond JCEF's
     * 100ms attachment timer; neither this callback nor the retry burst requests another repaint.
     */
    fun nativePainted() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        pending?.close()
        pending = null
        val current = ++generation
        if (isNativeVisible()) {
            pending = retries.after(PAINT_SETTLE_MILLIS) { attempt(current, 0) }
        }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        isClosed = true
        generation++
        pending?.close()
        pending = null
        component.removeHierarchyListener(hierarchy)
    }

    private fun attempt(current: Long, index: Int) {
        if (isClosed || generation != current || !isNativeVisible()) return
        try {
            val count = access.apply(component)
            if (count > 0) onClipped()
            if (index < RETRY_DELAYS.size) {
                pending = retries.after(RETRY_DELAYS[index]) { attempt(current, index + 1) }
            } else if (hasLoaded && count == 0) {
                fail(IllegalStateException("The native document did not attach to its owning window"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun isNativeVisible(): Boolean = component.isShowing && component.isDisplayable

    private fun fail(error: Throwable) {
        if (isClosed) return
        log.w(error.redactedFailure()) { "Native document clipping is unavailable" }
        close()
        onFailure()
    }

    private companion object {
        const val PAINT_SETTLE_MILLIS = 150
        const val FIRST_RETRY_MILLIS = 100
        const val SECOND_RETRY_MILLIS = 150
        const val LAST_RETRY_MILLIS = 250
        val RETRY_DELAYS = listOf(FIRST_RETRY_MILLIS, SECOND_RETRY_MILLIS, LAST_RETRY_MILLIS)
    }
}

/** Runs a complete scoped native operation synchronously; returns the number of matching content views. */
internal fun interface MacDocumentClipAccess {
    fun apply(component: JComponent): Int
}

/** One cancellable EDT callback; the owner controls the finite retry budget. */
internal fun interface DocumentClipRetries {
    fun after(milliseconds: Int, action: () -> Unit): AutoCloseable
}

private class SwingDocumentClipRetries : DocumentClipRetries {
    override fun after(milliseconds: Int, action: () -> Unit): AutoCloseable {
        val timer = Timer(milliseconds) { action() }.apply {
            isRepeats = false
            start()
        }
        return AutoCloseable { timer.stop() }
    }
}
