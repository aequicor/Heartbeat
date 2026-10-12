package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BrowserRendererLifecycleTest {
    @Test
    fun `crash destroys once and only explicit navigation requests one replacement`() {
        val events = mutableListOf<String>()
        val renderer = fixture(events)
        renderer.rendererGone()
        renderer.rendererGone()
        assertFalse(renderer.isActive)
        assertEquals(listOf("failure", "destroy:gone"), events)
        renderer.retry(BrowserViewCommand.Stop)
        renderer.retry(BrowserViewCommand.Back)
        renderer.retry(BrowserViewCommand.Forward)
        assertEquals(listOf("failure", "destroy:gone"), events)
        renderer.retry(BrowserViewCommand.Reload)
        renderer.retry(BrowserViewCommand.Load("https://example.com"))
        renderer.release()
        renderer.release()
        renderer.retry(BrowserViewCommand.Reload)
        assertEquals(listOf("failure", "destroy:gone", "recreate", "detach"), events)
    }

    @Test
    fun `load can recover a blank failed renderer but disposed surfaces stay inert`() {
        val events = mutableListOf<String>()
        val crashed = fixture(events)
        crashed.rendererGone()
        crashed.retry(BrowserViewCommand.Load("https://example.com"))
        assertEquals(listOf("failure", "destroy:gone", "recreate"), events)
        events.clear()
        val disposed = fixture(events)
        disposed.release()
        disposed.rendererGone()
        disposed.retry(BrowserViewCommand.Reload)
        assertFalse(disposed.isActive)
        assertEquals(listOf("detach", "destroy:active"), events)
    }

    private fun fixture(events: MutableList<String>) = BrowserRendererLifecycle(
        onFailure = { events += "failure" },
        onDestroy = { events += if (it) "destroy:gone" else "destroy:active" },
        onDetach = { events += "detach" },
        onRecreate = { events += "recreate" },
    )
}
