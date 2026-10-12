package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserAgentPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserSessionTest {
    @Test
    fun `a replaced control request survives an older transfer waiting for native cancellation`() = runTest {
        for (operation in listOf(BrowserOperation.ApproveControl, BrowserOperation.RejectControl)) {
            val cancelling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture(this, nativeCommand = {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cancelling.complete(Unit)
                        release.await()
                    }
                }
            })
            val tab = fixture.create()
            fixture.attach()
            runCurrent()
            val read = async { fixture.request(BrowserOperation.Read, tab) }
            runCurrent()
            val first = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
            val transfer = async { fixture.request(operation, tab, controlRequestId = first.requestId) }
            runCurrent()
            assertTrue(cancelling.isCompleted)
            val second = fixture.request(BrowserOperation.RequestControl, tab, "session-a")
            release.complete(Unit)
            assertNotNull(transfer.await().error)
            assertFailsWith<CancellationException> { read.await() }
            val pending = fixture.session.tabs.value.first.single()
            assertEquals(second.requestId, pending.controlRequestId)
            assertEquals("session-a", pending.pendingOwner)
            assertNull(pending.owner)
        }
    }

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
        assertEquals(1, fixture.clearCount)
        assertNotNull(fixture.request(BrowserOperation.ApproveControl, humanTab).error)
    }

    @Test
    fun `new tabs wait until persistent data clearing finishes`() = runTest {
        val released = CompletableDeferred<Unit>()
        val fixture = Fixture(this, released)
        fixture.create()
        val clearing = async { fixture.request(BrowserOperation.ClearData) }
        runCurrent()
        assertFalse(clearing.isCompleted)
        assertTrue(fixture.session.tabs.value.first.isEmpty())
        assertNotNull(fixture.request(BrowserOperation.NewTab, owner = "session-a").error)
        assertEquals(1, fixture.clearCount)
        released.complete(Unit)
        assertNull(clearing.await().error)
        assertNull(fixture.request(BrowserOperation.NewTab, owner = "session-a").error)
        assertEquals(1, fixture.session.tabs.value.first.size)
    }

    @Test
    fun `page load timeout is reported as an operation error rather than a cancellation`() = runTest {
        val fixture = Fixture(this, nativeCommand = { awaitCancellation() })
        val tab = fixture.create("session-a")
        fixture.attach()
        val result = fixture.session.perform(
            BrowserRequest(
                "navigate",
                BrowserOperation.Navigate,
                tab,
                "session-a",
                buildJsonObject { put("url", "https://example.com/slow") },
            ),
        )
        assertEquals("Page load timed out", result.error)
    }

    private class Fixture(
        scope: TestScope,
        private val clearRelease: CompletableDeferred<Unit>? = null,
        private val nativeCommand: (suspend () -> String)? = null,
    ) {
        val closed = mutableListOf<BrowserSurface>()
        private val panels = mutableListOf<DesktopBrowserPanel>()
        var clearCount = 0
        private var requestIndex = 0
        private val policy = object : BrowserAgentPolicy {
            private val enabled = MutableStateFlow(true)

            override fun observe() = enabled

            override suspend fun isEnabled() = enabled.value
        }
        private val dispatchers = BrowserTestDispatchers(StandardTestDispatcher(scope.testScheduler))
        private val resources = object : DesktopBrowserPanelFactory {
            override suspend fun clearData() {
                clearCount++
                clearRelease?.await()
            }

            override fun close(surface: BrowserSurface) {
                closed += surface
            }

            override fun create(surface: BrowserSurface) =
                DesktopBrowserPanel(surface, scope.backgroundScope, dispatchers) { _, _ ->
                    val command = checkNotNull(nativeCommand) { "Native runtime must not be created for empty tabs" }
                    object : DesktopBrowserRuntime {
                        override val component = JPanel()
                        override fun start(initialUrl: String) = Unit
                        override fun release() = Unit
                        override fun execute(command: BrowserViewCommand) = Unit
                        override suspend fun devTools(method: String, arguments: JsonObject): String = command()
                    }
                }.also(panels::add)
        }
        val session = DesktopBrowserSession(BrowserTestScope(scope.backgroundScope), dispatchers, resources, policy)

        fun attach() = SwingUtilities.invokeAndWait { panels.single().createComponent() }

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
