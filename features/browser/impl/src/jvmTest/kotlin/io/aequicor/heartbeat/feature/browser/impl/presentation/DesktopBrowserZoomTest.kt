package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserLoadFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserZoom
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserZoomStorage
import io.aequicor.heartbeat.feature.browser.impl.presentation.DesktopBrowserSessionTest.Fixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserZoomTest {
    @Test
    fun `zoom follows hosts across tabs navigation restart reset and live disabling`() = runTest {
        val storage = MemoryBrowserZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val first = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/a"))
        val second = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/b"))
        val other = fixture.create()
        fixture.session.execute(BrowserCommand.Load("http://example.com/c"))
        runCurrent()
        fixture.session.zoom(first, BrowserZoom.In)
        assertEquals(listOf(110, 110, 110), fixture.session.tabs.value.first.map { it.page.zoomPercent })
        fixture.session.selectTab(second)
        assertEquals(110, fixture.session.pages.value.zoomPercent)
        fixture.session.setZoomEnabled(false)
        assertEquals(listOf(100, 100, 100), fixture.session.tabs.value.first.map { it.page.zoomPercent })
        fixture.session.zoom(first, BrowserZoom.In)
        fixture.session.setZoomEnabled(true)
        assertEquals(110, fixture.session.pages.value.zoomPercent)
        fixture.session.selectTab(other)
        fixture.session.execute(BrowserCommand.Load("https://example.com/c"))
        runCurrent()
        assertEquals(110, fixture.session.pages.value.zoomPercent)
        val restored = Fixture(this, storage = fixture.storage, zoomStorage = storage)
        restored.session.setZoomEnabled(true)
        restored.session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(110, 110, 110), restored.session.tabs.value.first.map { it.page.zoomPercent })
        restored.session.zoom(first, BrowserZoom.Reset)
        assertEquals(listOf(100, 100, 100), restored.session.tabs.value.first.map { it.page.zoomPercent })
        assertEquals(emptyMap(), storage.load())
    }

    @Test
    fun `zooming a certificate warning keeps its native proceed decision`() = runTest {
        val url = "https://example.com/"
        val fixture = Fixture(this, devTools = { method, _ -> warningResponse(method, url) })
        fixture.errorPages.value = true
        fixture.session.setZoomEnabled(true)
        val tab = fixture.create()
        fixture.attach()
        runCurrent()
        val failure = BrowserLoadFailure(url, "ERR_CERT_DATE_INVALID", isCertificate = true, id = "one")
        fixture.emit(BrowserViewState(url = url, error = BrowserViewError.LoadFailed, failure = failure))
        runCurrent()
        assertEquals(true, fixture.session.pages.value.failure?.isProceedAllowed)
        fixture.session.zoom(tab, BrowserZoom.In)
        runCurrent()
        assertEquals(110, fixture.session.pages.value.zoomPercent)
        assertEquals(true, fixture.session.pages.value.failure?.isProceedAllowed)
    }

    @Test
    fun `unreadable saved levels leave the browser working at the default zoom`() = runTest {
        val fixture = Fixture(this, zoomStorage = UnreadableZoomStorage())
        fixture.session.setZoomEnabled(true)
        fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/"))
        runCurrent()
        assertEquals(100, fixture.session.pages.value.zoomPercent)
        assertEquals("https://example.com/", fixture.session.pages.value.url)
    }

    @Test
    fun `rapid queued steps use each preceding result while persistence is suspended`() = runTest {
        val storage = BlockingZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val tab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/"))
        val first = launch { fixture.session.zoom(tab, BrowserZoom.In) }
        storage.entered.await()
        val second = launch { fixture.session.zoom(tab, BrowserZoom.In) }
        val third = launch { fixture.session.zoom(tab, BrowserZoom.In) }
        runCurrent()
        assertEquals(100, fixture.session.pages.value.zoomPercent)
        storage.release.complete(Unit)
        first.join()
        second.join()
        third.join()
        assertEquals(mapOf("example.com" to 150), storage.load())
        assertEquals(150, fixture.session.pages.value.zoomPercent)
    }

    @Test
    fun `reset queued behind an uncommitted increase restores the default level`() = runTest {
        val storage = BlockingZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val tab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/"))
        val first = launch { fixture.session.zoom(tab, BrowserZoom.In) }
        storage.entered.await()
        val reset = launch { fixture.session.zoom(tab, BrowserZoom.Reset) }
        runCurrent()
        storage.release.complete(Unit)
        first.join()
        reset.join()
        assertEquals(emptyMap(), storage.load())
        assertEquals(100, fixture.session.pages.value.zoomPercent)
    }

    @Test
    fun `a queued zoom cannot save the previous host level for a newly navigated host`() = runTest {
        val storage = BlockingZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val tab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/a"))
        val first = launch { fixture.session.zoom(tab, BrowserZoom.In, "https://example.com/a") }
        storage.entered.await()
        val queued = launch { fixture.session.zoom(tab, BrowserZoom.In, "https://example.com/a") }
        runCurrent()
        fixture.session.execute(BrowserCommand.Load("https://other.example/b"))
        storage.release.complete(Unit)
        first.join()
        queued.join()
        runCurrent()
        assertEquals(mapOf("example.com" to 110), storage.load())
        assertEquals(100, fixture.session.pages.value.zoomPercent)
    }

    @Test
    fun `a queued zoom for a closed tab cannot change a surviving same-host tab`() = runTest {
        val storage = BlockingZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val firstTab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/a"))
        val closedTab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/b"))
        val first = launch { fixture.session.zoom(firstTab, BrowserZoom.In, "https://example.com/a") }
        storage.entered.await()
        val queued = launch { fixture.session.zoom(closedTab, BrowserZoom.In, "https://example.com/b") }
        runCurrent()
        fixture.request(BrowserOperation.CloseTab, closedTab)
        storage.release.complete(Unit)
        first.join()
        queued.join()
        runCurrent()
        assertEquals(mapOf("example.com" to 110), storage.load())
        assertEquals(110, fixture.session.pages.value.zoomPercent)
    }

    @Test
    fun `zoom accepts a changed scheme and port but rejects a different subdomain`() = runTest {
        val storage = MemoryBrowserZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val tab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("http://example.com:8080/new"))
        fixture.session.zoom(tab, BrowserZoom.In, "https://example.com/old")
        assertEquals(110, fixture.session.pages.value.zoomPercent)
        fixture.session.execute(BrowserCommand.Load("https://sub.example.com/"))
        fixture.session.zoom(tab, BrowserZoom.In, "https://example.com/old")
        runCurrent()
        assertEquals(mapOf("example.com" to 110), storage.load())
        assertEquals(100, fixture.session.pages.value.zoomPercent)
    }

    @Test
    fun `browser data removal forgets zoom and stale closed tab commands are ignored`() = runTest {
        val storage = MemoryBrowserZoomStorage()
        val fixture = Fixture(this, zoomStorage = storage)
        fixture.session.setZoomEnabled(true)
        val tab = fixture.create()
        fixture.session.execute(BrowserCommand.Load("https://example.com/a"))
        fixture.session.zoom(tab, BrowserZoom.In)
        fixture.request(BrowserOperation.CloseTab, tab)
        fixture.session.zoom(tab, BrowserZoom.In)
        assertEquals(110, storage.load()["example.com"])
        fixture.request(BrowserOperation.ClearData)
        assertEquals(emptyMap(), storage.load())
    }
}

/** A closed or corrupt profile store fails every read. */
private class UnreadableZoomStorage : BrowserZoomStorage {
    override suspend fun load(): Map<String, Int> = throw IllegalStateException("Profile store closed")

    override suspend fun save(host: String, percent: Int) = Unit

    override suspend fun clear() = Unit
}

/** Holds the first write open so navigation and queued commands can interleave without real-time sleeps. */
private class BlockingZoomStorage : BrowserZoomStorage {
    private val delegate = MemoryBrowserZoomStorage()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun load(): Map<String, Int> = delegate.load()

    override suspend fun save(host: String, percent: Int) {
        entered.complete(Unit)
        release.await()
        delegate.save(host, percent)
    }

    override suspend fun clear() = delegate.clear()
}

/** Chromium's native certificate interstitial for [url], with its proceed link. */
private fun warningResponse(method: String, url: String): String = when (method) {
    "Page.getFrameTree" ->
        """{"frameTree":{"frame":{"id":"main","loaderId":"1","url":"chrome-error://chromewebdata/","unreachableUrl":"$url"}}}"""

    "Page.createIsolatedWorld" -> """{"executionContextId":7}"""

    "Runtime.evaluate" -> """{"result":{"value":true}}"""

    else -> error("Unexpected CDP method: $method")
}
