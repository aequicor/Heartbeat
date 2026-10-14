package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.CompletableDeferred
import org.cef.CefApp

/**
 * Orders shutdown after CEF's real context callback, which can follow JCEF's INITIALIZED state.
 * A runtime closed before its first browser must not invalidate the global request context while
 * CEF's queued OnContextInitialized still needs it. Callers first join their native initializer.
 *
 * Native callbacks may arrive on different threads; the shared signals have no caller-owned parent.
 * Failed initialization and termination cannot produce another context callback and never wait for it.
 */
internal class DesktopBrowserRuntimeLifecycle {
    private val contextReady = CompletableDeferred<Boolean>()
    private val terminated = CompletableDeferred<Unit>()

    fun onContextInitialized() {
        contextReady.complete(true)
    }

    fun onStateChanged(state: CefApp.CefAppState) {
        when (state) {
            CefApp.CefAppState.INITIALIZATION_FAILED -> contextReady.complete(false)

            CefApp.CefAppState.TERMINATED -> {
                contextReady.complete(false)
                terminated.complete(Unit)
            }

            else -> Unit
        }
    }

    /** Suspends without blocking EDT; [dispose] must run on the runtime's initialization thread. */
    suspend fun shutdown(state: () -> CefApp.CefAppState, dispose: suspend () -> Unit) {
        val starting = state()
        val needsContext = starting == CefApp.CefAppState.INITIALIZING || starting == CefApp.CefAppState.INITIALIZED
        if (needsContext && !contextReady.await()) return
        // Re-read after the suspension: another owner may already have completed native termination.
        when (state()) {
            CefApp.CefAppState.NONE,
            CefApp.CefAppState.INITIALIZATION_FAILED,
            CefApp.CefAppState.TERMINATED,
            -> return

            CefApp.CefAppState.SHUTTING_DOWN -> Unit

            CefApp.CefAppState.NEW,
            CefApp.CefAppState.INITIALIZING,
            CefApp.CefAppState.INITIALIZED,
            -> dispose()
        }
        terminated.await()
    }
}
