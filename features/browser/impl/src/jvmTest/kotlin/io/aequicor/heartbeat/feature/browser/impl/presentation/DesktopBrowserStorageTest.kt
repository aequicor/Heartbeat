package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserTab
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserWorkspace
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserSessionTest.Fixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Chromium storage a tab is created with decides which cookies and signed-in sites its pages can see. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserStorageTest {
    @Test
    fun `a user tab uses the profile storage and an agent session gets storage of its own`() = runTest {
        val fixture = Fixture(this)
        fixture.create()
        fixture.create("session-a")
        fixture.create("session-b")
        fixture.create("session-a")
        assertEquals(
            listOf(
                BrowserStorage.User,
                BrowserStorage.Agent("session-a"),
                BrowserStorage.Agent("session-b"),
                BrowserStorage.Agent("session-a"),
            ),
            fixture.storages,
        )
    }

    @Test
    fun `a new tab of an agent never starts in the storage of a tab the user handed over`() = runTest {
        val fixture = Fixture(this)
        val handedOver = fixture.create()
        val pending = fixture.request(BrowserOperation.RequestControl, handedOver, "session-a")
        val approval =
            fixture.request(BrowserOperation.ApproveControl, handedOver, controlRequestId = pending.requestId)
        assertNull(approval.error)
        assertEquals("session-a", fixture.session.tabs.value.first.single().owner)
        assertNull(fixture.request(BrowserOperation.NewTab, handedOver, "session-a").error)
        assertEquals(listOf(BrowserStorage.User, BrowserStorage.Agent("session-a")), fixture.storages)
    }

    @Test
    fun `a link opens in the storage of the page that opened it`() = runTest {
        val fixture = Fixture(this)
        fixture.create()
        fixture.create("session-a")
        val handedOver = fixture.create()
        val pending = fixture.request(BrowserOperation.RequestControl, handedOver, "session-b")
        fixture.request(BrowserOperation.ApproveControl, handedOver, controlRequestId = pending.requestId)
        val next = BrowserCommand.Load("https://example.com/next")
        fixture.links[0](next)
        fixture.links[1](next)
        fixture.links[2](next)
        runCurrent()
        assertEquals(
            listOf(BrowserStorage.User, BrowserStorage.Agent("session-a"), BrowserStorage.User),
            fixture.storages.drop(3),
        )
        // The link opened from the handed over tab belongs to the agent that holds it, in the user's storage.
        assertEquals(2, fixture.session.tabs.value.first.count { it.owner == "session-b" })
    }

    @Test
    fun `restored tabs get the storage of their owner`() = runTest {
        val saved = SavedBrowserWorkspace(
            listOf(
                SavedBrowserTab("mine", "https://example.com/", "Mine", null, ""),
                SavedBrowserTab("agent", "https://example.org/", "Agent", "session-a", ""),
            ),
        )
        val fixture = Fixture(this, storage = MemoryBrowserWorkspaceStorage(saved))
        fixture.session.setEnabled(true)
        assertEquals(listOf(BrowserStorage.User, BrowserStorage.Agent("session-a")), fixture.storages)
    }
}
