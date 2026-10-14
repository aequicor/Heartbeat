package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HbPopupFocusRequestsTest {
    @Test
    fun `repeated showing events coalesce while sibling popups keep independent requests`() {
        val callbacks = mutableListOf<() -> Unit>()
        val requested = mutableListOf<String>()
        val requests = HbPopupFocusRequests<String>({ callbacks += it }, { requested += it })
        requests.shown("first")
        requests.shown("first")
        requests.shown("second")
        callbacks.toList().forEach { it() }
        assertEquals(listOf("first", "second"), requested)
    }

    @Test
    fun `hiding and reopening before dispatch cannot let the stale callback consume the new request`() {
        val callbacks = mutableListOf<() -> Unit>()
        val requested = mutableListOf<String>()
        val requests = HbPopupFocusRequests<String>({ callbacks += it }, { requested += it })
        requests.shown("popup")
        requests.hidden("popup")
        requests.shown("popup")
        callbacks[0]()
        assertTrue(requested.isEmpty())
        callbacks[1]()
        assertEquals(listOf("popup"), requested)
    }

    @Test
    fun `a hidden popup cannot issue its queued handoff`() {
        val callbacks = mutableListOf<() -> Unit>()
        val requests = HbPopupFocusRequests<String>({ callbacks += it }, { error("Hidden popup was activated") })
        requests.shown("popup")
        requests.hidden("popup")
        callbacks.single()()
    }

    @Test
    fun `closing the listener cancels queued requests and rejects later showing events`() {
        val callbacks = mutableListOf<() -> Unit>()
        val requests = HbPopupFocusRequests<String>({ callbacks += it }, { error("Closed listener was invoked") })
        requests.shown("popup")
        requests.close()
        requests.close()
        requests.shown("later")
        callbacks.single()()
        assertEquals(1, callbacks.size)
    }
}
