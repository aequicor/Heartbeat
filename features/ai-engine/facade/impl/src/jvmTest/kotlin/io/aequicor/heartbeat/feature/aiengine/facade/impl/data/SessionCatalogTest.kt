package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchiveFilter
import io.aequicor.heartbeat.feature.aiengine.facade.api.DiscoveryStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.BlockedEngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnabledEngines
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FakeEngineToggles
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FakeListing
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FakeSessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FakeStoredSession
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StoredSession
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestEngine
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.facadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.registration
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.sessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCatalogTest {
    private val database = Room.inMemoryDatabaseBuilder<SessionIndexDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val listing = FakeListing()
    private val source = FakeSessionSource(discovery = listing)
    private val toggles = FakeEngineToggles()

    @AfterTest
    fun close() = database.close()

    private fun TestScope.catalog(profile: String = "alice", settle: Boolean = true): SessionCatalogService {
        val registry = EngineRegistry(listOf(registration(sources = listOf(source))), EnginePlatform.DesktopWindows)
        val context = facadeContext()
        return SessionCatalogService(
            EnabledEngines(registry, toggles, context.scope),
            RoomSessionIndex(database),
            SessionCursorCodec(profile),
            context,
        ) { _, stored -> StoredSession(stored) { FeatureAccess.Unsupported } }.also { if (settle) runCurrent() }
    }

    @Test
    fun `refresh indexes every native page and pages are ordered newest first`() = runTest {
        val catalog = catalog()
        listing.items = listOf(summary("a", 10), summary("b", 30), summary("c", null), summary("d", 20))

        val report = catalog.refresh()
        val first = catalog.page(request = PageRequest(limit = 2))
        val second = catalog.page(request = PageRequest(first.next, limit = 2))

        assertEquals(DiscoveryStatus.Complete, report.sources.single().status)
        assertEquals(listOf("b", "d"), first.items.map { it.ref.nativeId })
        assertEquals(listOf("a", "c"), second.items.map { it.ref.nativeId })
        assertNull(second.next)
        assertEquals(DiscoveryStatus.Complete, second.sources.single().status)
        assertEquals(setOf(TestEngine), listing.queries.first().engines)
    }

    @Test
    fun `cursors are bound to their snapshot, query and profile`() = runTest {
        val catalog = catalog()
        listing.items = listOf(summary("a", 1), summary("b", 2), summary("c", 3))
        catalog.refresh()
        val cursor = catalog.page(request = PageRequest(limit = 1)).next

        val otherQuery = assertFailsWith<EngineException> {
            catalog.page(SessionQuery(search = "x"), PageRequest(cursor, limit = 1))
        }
        assertEquals(
            EngineFailure.Request(io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason.Invalid),
            otherQuery.failure,
        )
        assertFailsWith<EngineException> { catalog("bob").page(request = PageRequest(cursor, limit = 1)) }

        catalog.refresh()
        val stale = assertFailsWith<EngineException> { catalog.page(request = PageRequest(cursor, limit = 1)) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), stale.failure)
    }

    @Test
    fun `failed discovery keeps previous entries and reports partial coverage`() = runTest {
        val catalog = catalog()
        listing.items = listOf(summary("a", 1))
        catalog.refresh()

        val outage = EngineFailure.Transport(TransportFailureReason.Timeout)
        listing.failure = EngineException(outage)
        val report = catalog.refresh()

        assertEquals(DiscoveryStatus.Unavailable(outage), report.sources.single().status)
        val page = catalog.page()
        assertEquals(listOf("a"), page.items.map { it.ref.nativeId })
        assertEquals(DiscoveryStatus.Unavailable(outage), page.sources.single().status)
    }

    @Test
    fun `complete enumeration drops vanished external sessions but keeps Heartbeat provenance`() = runTest {
        val catalog = catalog()
        listing.items = listOf(summary("external", 1), summary("created", 2))
        catalog.refresh()
        catalog.record(summary("created", 2, origin = SessionOrigin.Heartbeat))
        catalog.record(summary("fresh", 3, origin = SessionOrigin.Heartbeat))

        listing.items = listOf(summary("created", 5, title = "Renamed"))
        catalog.refresh()

        val items = catalog.page().items
        assertEquals(listOf("created", "fresh"), items.map { it.ref.nativeId })
        assertEquals(SessionOrigin.Heartbeat, items.first().origin)
        assertEquals("Renamed", items.first().title)
    }

    @Test
    fun `filters apply search, archive state and disabled engines`() = runTest {
        val catalog = catalog()
        listing.items = listOf(
            summary("a", 1, title = "Fix 100% of bugs"),
            summary("b", 2, title = "Refactor", isArchived = true),
        )
        catalog.refresh()

        assertEquals(listOf("a"), catalog.page(SessionQuery(search = "100%")).items.map { it.ref.nativeId })
        assertEquals(
            listOf("b"),
            catalog.page(SessionQuery(archive = ArchiveFilter.Archived)).items.map { it.ref.nativeId },
        )
        assertTrue(catalog.page(SessionQuery(sources = setOf(SessionSourceId("other")))).items.isEmpty())

        toggles.disabled.value = setOf(TestEngine)
        runCurrent()
        assertTrue(catalog.page().items.isEmpty())
    }

    @Test
    fun `stored sessions expose adapter history but resume only through the facade`() = runTest {
        val catalog = catalog()
        val ref = sessionRef("a")
        val history = BlockedEngineFeatures(EngineFailure.Unknown())
        source.stored[ref] = FakeStoredSession(summary("a"), history)

        val session = catalog.get(ref)
        assertIs<FeatureAccess.Unavailable>(session.features.resolve(SessionHistory))
        assertEquals(FeatureAccess.Unsupported, session.features.resolve(ResumesSessions))

        val missing = assertFailsWith<EngineException> {
            catalog.get(
                sessionRef("a", source = SessionSourceId("gone")),
            )
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.NotFound), missing.failure)
    }

    @Test
    fun `adapter claims never grant Heartbeat provenance and known metadata survives sparse listings`() = runTest {
        val catalog = catalog()
        listing.items = listOf(
            summary("claimed", 1, origin = SessionOrigin.Heartbeat),
            summary("titled", 2, title = "Kept"),
        )
        catalog.refresh()
        listing.items = listOf(summary("titled", 3))
        catalog.refresh()

        val items = catalog.page().items
        assertEquals(listOf("titled"), items.map { it.ref.nativeId })
        assertEquals("Kept", items.single().title)
    }

    @Test
    fun `pages read the toggles before their first observation`() = runTest {
        val catalog = catalog(settle = false)
        listing.items = listOf(summary("a", 1))
        catalog.refresh()

        assertEquals(listOf("a"), catalog.page().items.map { it.ref.nativeId })
    }

    @Test
    fun `stored sessions read the toggles before their first observation`() = runTest {
        val catalog = catalog(settle = false)
        val ref = sessionRef("a")
        source.stored[ref] = FakeStoredSession(summary("a"), BlockedEngineFeatures(EngineFailure.Unknown()))

        assertEquals(ref, catalog.get(ref).summary.value.ref)
    }
}
