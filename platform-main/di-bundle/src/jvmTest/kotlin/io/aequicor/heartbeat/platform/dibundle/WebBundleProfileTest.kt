package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser comes from the `web` bundle's own graphs on desktop. The app graph here is the foundation only, so
 * each service below is proof that the bundle-assembled graph contributes what the monolith used to.
 */
class WebBundleProfileTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `profile startups of the bundle run with the profile`() = runTest {
        val session = app.profileSessions.open(ProfileId("web-startups"))
        val names = (session.graph as ProfileStartupAccess).profileStartups.map { it::class.simpleName }
        assertTrue("BrowserStartup" in names, names.toString())
        assertTrue(session.startups.any { it::class.simpleName == "BrowserStartup" })
    }

    @Test
    fun `documents come from the bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("web-documents"))
        val documents = (session.graph as TestWebProfileAccessors).webDocuments
        assertTrue(documents !is UnavailableBrowserDocuments, "the bundle documents must be wired in, not the fallback")
        val handle = assertNotNull(documents.open(BrowserDocument("<p>Map</p>", "Map")))
        handle.close()
    }

    @Test
    fun `document previews come from the bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("web-previews"))
        val previews = (session.graph as TestWebProfileAccessors).browserDocumentPreviews
        assertTrue(previews !is UnavailableBrowserDocumentPreviews, "the bundle previews must be wired in")
    }

    @Test
    fun `agent tools of both browsers reach the tool set of the engines bundle`() = runTest {
        val session = app.profileSessions.open(ProfileId("web-tools"))
        val groups = (session.graph as TestWebProfileAccessors).webAgentTools.catalog().map { it.id }
        assertTrue("browser" in groups, groups.toString())
        assertTrue("external_browser" in groups, groups.toString())
    }

    @Test
    fun `application services of the bundle reach the foundation sets`() = runTest {
        val shutdown = (app as AppShutdownAccessors).shutdownParticipants.map { it::class.simpleName }
        assertTrue("DesktopBrowserShutdown" in shutdown, shutdown.toString())
        val cleaners = (app as TestWebAppAccessors).profileStorageCleaners.map { it::class.simpleName }
        assertTrue("DesktopBrowserProfileCleaner" in cleaners, cleaners.toString())
    }

    @Test
    fun `closing the profile drops its bundle part and the foundation falls back`() = runTest {
        val session = app.profileSessions.open(ProfileId("web-close"))
        val graph = session.graph as TestWebProfileAccessors
        assertNotNull(graph.webDocuments.open(BrowserDocument("<p>Open</p>", "Open")))
        app.profileSessions.close()
        assertNull(graph.webDocuments.open(BrowserDocument("<p>Closed</p>", "Closed")))
        val preview = graph.browserDocumentPreviews.preview(BrowserDocumentPreviewRequest("<p>x</p>"))
        assertTrue(preview is BrowserDocumentPreviewResult.Unavailable)
    }
}

/** Test access to what the foundation profile graph takes from the `web` bundle. */
@ContributesTo(ProfileScope::class)
interface TestWebProfileAccessors {
    val webDocuments: BrowserDocuments
    val browserDocumentPreviews: BrowserDocumentPreviews
    val webAgentTools: ProfileAgentTools
}

/** Test access to what the foundation app graph takes from the `web` bundle. */
@ContributesTo(AppScope::class)
interface TestWebAppAccessors {
    val profileStorageCleaners: Set<ProfileStorageCleaner>
}
