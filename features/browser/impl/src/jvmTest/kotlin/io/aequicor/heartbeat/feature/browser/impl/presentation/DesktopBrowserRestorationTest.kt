package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserWorkspaceStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserTab
import io.aequicor.heartbeat.feature.browser.impl.domain.SavedBrowserWorkspace
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserSessionTest.Fixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserRestorationTest {
    @Test
    fun `a new session restores tab identities groups addresses titles order and selection`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage()
        val first = Fixture(this, storage = storage)
        val personal = first.create()
        first.session.execute(BrowserCommand.Load("https://example.com/personal"))
        val controller = RecordingBrowserController()
        first.surfaces.first().attached(controller)
        runCurrent()
        first.surfaces.first().changed(controller, BrowserViewState("https://example.com/personal", "Personal"))
        runCurrent()
        val agent = first.create("session-a")
        first.session.execute(BrowserCommand.Load("https://example.com/agent"))
        first.create()
        first.session.selectTab(personal)
        first.request(BrowserOperation.RequestControl, personal, "session-b")
        runCurrent()
        val saved = storage.workspace
        val restarted = Fixture(this, storage = storage)
        restarted.session.setEnabled(true)
        runCurrent()
        val (tabs, selected) = restarted.session.tabs.value
        assertEquals(saved.tabs.map { it.id }, tabs.map { it.id })
        assertEquals(saved.tabs.map { it.url }, tabs.map { it.page.url })
        assertEquals(saved.tabs.map { it.owner }, tabs.map { it.owner })
        assertEquals(personal, selected)
        assertEquals("Personal", restarted.session.pages.value.title)
        assertTrue(tabs.all { it.isHumanControlled && it.pendingOwner == null && it.controlRequestId == null })
        assertTrue(restarted.guards.none { it.isRestricted })
        assertTrue(restarted.request(BrowserOperation.Read, agent, "session-a").error != null)
        restarted.session.setEnabled(true)
        assertEquals(saved.tabs.size, restarted.surfaces.size)
    }

    @Test
    fun `closing tabs persists removal including the last tab`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage()
        val first = Fixture(this, storage = storage)
        val one = first.create()
        val two = first.create()
        first.request(BrowserOperation.CloseTab, two)
        assertEquals(listOf(one), storage.workspace.tabs.map { it.id })
        val next = Fixture(this, storage = storage)
        next.session.setEnabled(true)
        assertEquals(one, next.session.tabs.value.second)
        next.request(BrowserOperation.CloseTab, one)
        val last = Fixture(this, storage = storage)
        last.session.setEnabled(true)
        assertTrue(last.session.tabs.value.first.isEmpty())
        assertNull(last.session.tabs.value.second)
    }

    @Test
    fun `disabling releases surfaces without erasing the saved workspace`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage()
        val fixture = Fixture(this, storage = storage)
        val tab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/"))
        fixture.session.setEnabled(false)
        runCurrent()
        assertTrue(fixture.session.tabs.value.first.isEmpty())
        assertEquals(tab, storage.workspace.selectedTabId)
        val disabled = Fixture(this, storage = storage)
        disabled.session.setEnabled(false)
        assertTrue(disabled.surfaces.isEmpty())
        fixture.session.setEnabled(true)
        assertEquals(tab, fixture.session.tabs.value.second)
        assertEquals("https://example.com/", fixture.session.pages.value.url)
    }

    @Test
    fun `clear removes recovery data before native deletion completes`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, clearRelease = release, storage = storage)
        fixture.create()
        val clear = async { fixture.request(BrowserOperation.ClearData) }
        runCurrent()
        assertEquals(1, fixture.clearCount)
        assertEquals(SavedBrowserWorkspace(), storage.workspace)
        val restarted = Fixture(this, storage = storage)
        restarted.session.setEnabled(true)
        assertTrue(restarted.session.tabs.value.first.isEmpty())
        release.complete(Unit)
        assertNull(clear.await().error)
    }

    @Test
    fun `failed restoration keeps the journal and closes partial native surfaces before retry`() = runTest {
        val saved = SavedBrowserWorkspace(listOf(SavedBrowserTab("one"), SavedBrowserTab("two")), "two")
        val storage = MemoryBrowserWorkspaceStorage(saved)
        val broken = Fixture(this, storage = storage, failCreateAt = 1)
        assertFailsWith<IllegalStateException> { broken.session.setEnabled(true) }
        assertEquals(saved, storage.workspace)
        assertEquals(1, broken.closed.size)
        assertTrue(broken.session.tabs.value.first.isEmpty())
        val retry = Fixture(this, storage = storage)
        retry.session.setEnabled(true)
        assertEquals(listOf("one", "two"), retry.session.tabs.value.first.map { it.id })
    }

    @Test
    fun `read failure never enables or overwrites an unread journal`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage(SavedBrowserWorkspace(listOf(SavedBrowserTab("saved")), "saved"))
        storage.isReadFailing = true
        val fixture = Fixture(this, storage = storage)
        assertFailsWith<IllegalStateException> { fixture.session.setEnabled(true) }
        fixture.session.execute(BrowserCommand.Load("https://example.com/"))
        assertTrue(fixture.surfaces.isEmpty())
        assertEquals("saved", storage.workspace.selectedTabId)
        storage.isReadFailing = false
        fixture.session.setEnabled(true)
        assertEquals("saved", fixture.session.tabs.value.second)
    }

    @Test
    fun `failed journal deletion prevents native clear and can be retried`() = runTest {
        val storage = MemoryBrowserWorkspaceStorage()
        val fixture = Fixture(this, storage = storage)
        fixture.create()
        storage.isWriteFailing = true
        assertTrue(fixture.request(BrowserOperation.ClearData).error != null)
        assertEquals(0, fixture.clearCount)
        assertFalse(fixture.session.tabs.value.first.isEmpty())
        storage.isWriteFailing = false
        assertNull(fixture.request(BrowserOperation.ClearData).error)
        assertEquals(SavedBrowserWorkspace(), storage.workspace)
    }
}

internal class MemoryBrowserWorkspaceStorage(var workspace: SavedBrowserWorkspace = SavedBrowserWorkspace()) :
    BrowserWorkspaceStorage {
    var isReadFailing = false
    var isWriteFailing = false

    override suspend fun load(): SavedBrowserWorkspace {
        check(!isReadFailing) { "Journal read failed" }
        return workspace
    }

    override suspend fun save(workspace: SavedBrowserWorkspace) {
        check(!isWriteFailing) { "Journal write failed" }
        this.workspace = workspace
    }
}
