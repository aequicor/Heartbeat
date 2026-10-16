package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter

/**
 * Windowed CEF mouse events bypass Swing, and macOS Chromium does not zoom on a modified wheel. Each frame gets a
 * non-passive listener that consumes only trusted vertical wheel events with Ctrl or Cmd. The private presentation
 * channel can only request a bounded zoom step for its own tab; it exposes no browser operations or profile data.
 * The host rechecks the live toggle and selected tab. A page may invoke the channel itself, so requests never carry
 * an arbitrary percentage or target origin and the host throttles them slightly below the listener's rate, which
 * bounds profile writes. Installation is idempotent and disabling removes each listener.
 */
internal class DesktopBrowserWheelZoom(
    private val isEnabled: () -> Boolean,
    onShortcut: (BrowserShortcut) -> Unit,
    throttle: BrowserWheelZoomThrottle = BrowserWheelZoomThrottle(),
) {
    private val names = desktopDocumentRouterConfig()
    private val handler = object : CefMessageRouterHandlerAdapter() {
        @HighFrequency
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            val direction = when (request) {
                "in" -> BrowserZoomDirection.In
                "out" -> BrowserZoomDirection.Out
                else -> null
            }
            if (!isEnabled() || persistent || direction == null) {
                callback.failure(0, "Zoom unavailable")
            } else if (!throttle.tryAcquire()) {
                callback.failure(0, "Zoom throttled")
            } else {
                onShortcut(BrowserShortcut.Zoom(direction))
                callback.success("null")
            }
            return true
        }
    }
    val router: CefMessageRouter = CefMessageRouter.create(names).also { it.addHandler(handler, true) }

    fun install(frame: CefFrame) {
        val script = desktopBrowserWheelScript(names.jsQueryFunction, isEnabled())
        frame.executeJavaScript(script, frame.url, 0)
    }

    fun close() {
        router.removeHandler(handler)
    }
}

/**
 * Accepts one step per [HOST_ZOOM_INTERVAL_MS] on a monotonic clock; CEF calls it on its UI thread. The bound is
 * below the listener's [WHEEL_ZOOM_INTERVAL_MS], so consecutive listener steps tolerate up to 30 ms of delivery
 * jitter; a step from another frame of the tab sooner than the bound is still dropped.
 */
internal class BrowserWheelZoomThrottle(private val nanoTime: () -> Long = System::nanoTime) {
    private var lastAccepted: Long? = null

    fun tryAcquire(): Boolean {
        val now = nanoTime()
        val last = lastAccepted
        if (last != null && now - last < HOST_ZOOM_INTERVAL_MS * NANOS_PER_MILLI) return false
        lastAccepted = now
        return true
    }
}

private const val WHEEL_ZOOM_INTERVAL_MS = 80L
private const val HOST_ZOOM_INTERVAL_MS = 50L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Captures a native query function once; ordinary scrolling and horizontal gestures remain untouched. Repeated
 * installation keeps an enabled listener, so its throttle survives and a later install can repair a missed one.
 */
internal fun desktopBrowserWheelScript(query: String, isEnabled: Boolean): String = """
    (() => {
      const key = '${query}_wheel';
      if ($isEnabled && window[key]) return;
      if (window[key]) { window.removeEventListener('wheel', window[key], true); delete window[key]; }
      if (!$isEnabled) return;
      const send = window['$query'];
      if (typeof send !== 'function') return;
      let last = 0;
      const listener = event => {
        if (!event.isTrusted || !(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey || !event.deltaY) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        const now = performance.now();
        if (last && now - last < $WHEEL_ZOOM_INTERVAL_MS) return;
        last = now;
        send({request: event.deltaY < 0 ? 'in' : 'out', onFailure: () => {}});
      };
      window[key] = listener;
      window.addEventListener('wheel', listener, {capture: true, passive: false});
    })();
    """.trimIndent()
