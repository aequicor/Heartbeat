package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cef.CefApp.CefAppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserRuntimeLifecycleTest {
    @Test
    fun `closing a newly initialized empty runtime waits for the real context callback`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        var state = CefAppState.INITIALIZED
        var disposals = 0
        val shutdown = backgroundScope.async {
            lifecycle.shutdown({ state }) {
                disposals++
                state = CefAppState.SHUTTING_DOWN
            }
        }
        lifecycle.onStateChanged(CefAppState.INITIALIZED)
        runCurrent()
        assertEquals(0, disposals)
        assertFalse(shutdown.isCompleted)

        lifecycle.onContextInitialized()
        runCurrent()
        assertEquals(1, disposals)
        assertFalse(shutdown.isCompleted)

        state = CefAppState.TERMINATED
        lifecycle.onStateChanged(state)
        shutdown.await()
    }

    @Test
    fun `a context callback before Java initialization finishes is retained for shutdown`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        lifecycle.onContextInitialized()
        lifecycle.onStateChanged(CefAppState.INITIALIZED)
        var disposals = 0
        lifecycle.shutdown({ CefAppState.INITIALIZED }) {
            disposals++
            lifecycle.onStateChanged(CefAppState.TERMINATED)
        }
        assertEquals(1, disposals)
    }

    @Test
    fun `failed unstarted and terminated runtimes never wait for a nonexistent context callback`() = runTest {
        for (state in listOf(CefAppState.NONE, CefAppState.INITIALIZATION_FAILED, CefAppState.TERMINATED)) {
            val lifecycle = DesktopBrowserRuntimeLifecycle()
            lifecycle.shutdown({ state }) { error("A non-running runtime must not be disposed") }
        }
    }

    @Test
    fun `termination while waiting for context initialization releases shutdown without disposing again`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        var state = CefAppState.INITIALIZED
        val shutdown = backgroundScope.async {
            lifecycle.shutdown({ state }) { error("Terminated runtime must not be disposed again") }
        }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        state = CefAppState.TERMINATED
        lifecycle.onStateChanged(state)
        shutdown.await()
    }

    @Test
    fun `an uninitialized app is disposed without waiting for context readiness`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        var disposals = 0
        lifecycle.shutdown({ CefAppState.NEW }) {
            disposals++
            lifecycle.onStateChanged(CefAppState.TERMINATED)
        }
        assertEquals(1, disposals)
    }

    @Test
    fun `an already shutting down runtime waits only for termination`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        val shutdown = backgroundScope.async {
            lifecycle.shutdown({ CefAppState.SHUTTING_DOWN }) { error("Shutdown already started") }
        }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        lifecycle.onStateChanged(CefAppState.TERMINATED)
        shutdown.await()
    }

    @Test
    fun `cancelled callers cannot cancel the shared native readiness signal`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        val cancelled = backgroundScope.async {
            lifecycle.shutdown({ CefAppState.INITIALIZED }) { error("Context is not ready") }
        }
        runCurrent()
        cancelled.cancelAndJoin()
        lifecycle.onContextInitialized()
        var disposed = false
        lifecycle.shutdown({ CefAppState.INITIALIZED }) {
            disposed = true
            lifecycle.onStateChanged(CefAppState.TERMINATED)
        }
        assertTrue(disposed)
    }

    @Test
    fun `a late native callback still allows cleanup when Java remains initializing`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        var disposals = 0
        val shutdown = backgroundScope.async {
            lifecycle.shutdown({ CefAppState.INITIALIZING }) {
                disposals++
                lifecycle.onStateChanged(CefAppState.TERMINATED)
            }
        }
        runCurrent()
        assertEquals(0, disposals)
        assertFalse(shutdown.isCompleted)
        lifecycle.onContextInitialized()
        shutdown.await()
        assertEquals(1, disposals)
    }

    @Test
    fun `initialization failure releases a pending shutdown without requiring context readiness`() = runTest {
        val lifecycle = DesktopBrowserRuntimeLifecycle()
        var state = CefAppState.INITIALIZING
        val shutdown = backgroundScope.async {
            lifecycle.shutdown({ state }) { error("Native initialization failed") }
        }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        state = CefAppState.INITIALIZATION_FAILED
        lifecycle.onStateChanged(state)
        shutdown.await()
    }
}
