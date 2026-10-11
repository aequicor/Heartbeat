package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBrowserSessionTest {
    @Test
    fun `agent sessions cannot read or close each others tabs and human takeover revokes access`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create("session-a")
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-b").error)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-b").error)
        assertEquals(0, fixture.list("session-b").size)
        assertNull(fixture.request(BrowserOperation.TakeControl, tab).error)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
        assertNull(fixture.request(BrowserOperation.ReturnControl, tab).error)
        assertNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
    }

    @Test
    fun `requesting a human tab grants no access until UI approves the requesting session`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        assertNull(fixture.request(BrowserOperation.RequestControl, tab, "session-a").error)
        val pending = fixture.session.tabs.value.first.single()
        assertEquals("session-a", pending.pendingOwner)
        assertNull(pending.owner)
        assertEquals(0, fixture.list("session-a").size)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab, "session-a").error)
        assertNull(
            fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = pending.controlRequestId).error,
        )
        val approved = fixture.session.tabs.value.first.single()
        assertEquals("session-a", approved.owner)
        assertNull(approved.pendingOwner)
        assertNull(approved.controlRequestId)
        assertFalse(approved.isHumanControlled)
        assertEquals(1, fixture.list("session-a").size)
        assertEquals(0, fixture.list("session-b").size)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-b").error)
        assertNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
    }

    @Test
    fun `another session cannot replace or reject a pending request`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        val request = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNull(request.error)
        assertNotNull(fixture.request(BrowserOperation.RequestControl, tab, "session-b").error)
        assertNotNull(fixture.request(BrowserOperation.RejectControl, tab, "session-b").error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().pendingOwner)
        assertNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = request.requestId).error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().owner)
    }

    @Test
    fun `rejection keeps the tab human owned and a later approval requires a new request`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        val first = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNull(fixture.request(BrowserOperation.RejectControl, tab, controlRequestId = first.requestId).error)
        val rejected = fixture.session.tabs.value.first.single()
        assertNull(rejected.owner)
        assertNull(rejected.pendingOwner)
        assertNull(rejected.controlRequestId)
        assertTrue(rejected.isHumanControlled)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab).error)
        val second = fixture.request(BrowserOperation.RequestControl, tab, "session-b")
        assertNull(second.error)
        assertNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = second.requestId).error)
        assertEquals("session-b", fixture.session.tabs.value.first.single().owner)
        assertNotNull(fixture.request(BrowserOperation.CloseTab, tab, "session-a").error)
    }

    @Test
    fun `stale approval and rejection cannot authorize or clear the next requesting session`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create()
        val first = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
        assertNull(fixture.request(BrowserOperation.RejectControl, tab, controlRequestId = first.requestId).error)
        val second = fixture.request(BrowserOperation.RequestControl, tab, "session-b")
        val pending = fixture.session.tabs.value.first.single()
        assertEquals("session-b", pending.pendingOwner)
        assertEquals(second.requestId, pending.controlRequestId)
        for (operation in listOf(BrowserOperation.ApproveControl, BrowserOperation.RejectControl)) {
            assertNotNull(fixture.request(operation, tab, controlRequestId = first.requestId).error)
            assertEquals(pending, fixture.session.tabs.value.first.single())
        }
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, tab).error)
        assertEquals(pending, fixture.session.tabs.value.first.single())
        assertNull(fixture.request(BrowserOperation.ApproveControl, tab, controlRequestId = second.requestId).error)
        assertEquals("session-b", fixture.session.tabs.value.first.single().owner)
        assertNotNull(fixture.request(BrowserOperation.Read, tab, "session-a").error)
    }

    @Test
    fun `control requests cannot transfer an existing agent owned tab`() = runTest {
        val fixture = Fixture(this)
        val tab = fixture.create("session-a")
        assertNotNull(fixture.request(BrowserOperation.RequestControl, tab, "session-b").error)
        assertNotNull(fixture.request(BrowserOperation.RequestControl, tab).error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().owner)
        assertNull(fixture.session.tabs.value.first.single().pendingOwner)
    }

    @Test
    fun `only the user may clear all browsing contexts including agent and pending tabs`() = runTest {
        val fixture = Fixture(this)
        val humanTab = fixture.create()
        fixture.create("session-a")
        fixture.request(BrowserOperation.RequestControl, humanTab, "session-b")
        assertNotNull(fixture.request(BrowserOperation.ClearData, owner = "session-a").error)
        assertEquals(2, fixture.session.tabs.value.first.size)
        assertTrue(fixture.closed.isEmpty())
        assertNull(fixture.request(BrowserOperation.ClearData).error)
        assertEquals(emptyList(), fixture.session.tabs.value.first)
        assertNull(fixture.session.tabs.value.second)
        assertEquals(2, fixture.closed.size)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, humanTab).error)
    }

    private class Fixture(scope: TestScope) {
        val closed = mutableListOf<BrowserSurface>()
        private var requestIndex = 0
        private val policy = object : BrowserAgentPolicy {
            private val enabled = MutableStateFlow(true)

            override fun observe() = enabled

            override suspend fun isEnabled() = enabled.value
        }
        private val dispatchers = BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        private val resources = object : DesktopBrowserPanelFactory {
            override fun close(surface: BrowserSurface) {
                closed += surface
            }

            override fun create(surface: BrowserSurface) =
                DesktopBrowserPanel(surface, scope.backgroundScope, dispatchers) { _, _ ->
                    error("Native runtime must not be created for empty tabs")
                }
        }
        val session = DesktopBrowserSession(BrowserTestScope(scope.backgroundScope), dispatchers, resources, policy)

        suspend fun create(owner: String? = null): String {
            session.setEnabled(true)
            return request(BrowserOperation.NewTab, owner = owner).data.getValue("tabId").jsonPrimitive.content
        }

        suspend fun request(
            operation: BrowserOperation,
            tab: String? = null,
            owner: String? = null,
            controlRequestId: String? = null,
        ): BrowserResult = session.perform(
            BrowserRequest(
                "request-${requestIndex++}",
                operation,
                tab,
                owner,
                buildJsonObject { controlRequestId?.let { put("controlRequestId", it) } },
            ),
        )

        suspend fun list(owner: String) =
            request(BrowserOperation.ListTabs, owner = owner).data.getValue("tabs").jsonArray
    }
}
