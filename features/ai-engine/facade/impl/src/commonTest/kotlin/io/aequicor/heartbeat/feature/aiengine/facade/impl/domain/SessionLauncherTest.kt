package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SourceDiscovery
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Index keeping only what the launcher records. */
internal class RecordingSessionIndex : SessionIndex {
    val entries = mutableMapOf<SessionRef, SessionSummary>()

    override suspend fun revision(): Long = 0

    override suspend fun advanceRevision(): Long = 0

    override suspend fun page(query: SessionQuery, after: IndexPosition?, limit: Int) = entries.values.toList()

    override suspend fun find(ref: SessionRef): SessionSummary? = entries[ref]

    override suspend fun upsertDiscovered(entries: List<SessionSummary>, generation: Long) {
        entries.forEach { this.entries[it.ref] = it }
    }

    override suspend fun record(entry: SessionSummary) {
        entries[entry.ref] = entry
    }

    override suspend fun removeMissing(engine: EngineId, source: SessionSourceId, generation: Long) = Unit

    override suspend fun coverage(): List<SourceDiscovery> = emptyList()

    override suspend fun saveCoverage(discovery: SourceDiscovery) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLauncherTest {
    private val runtimes = mutableListOf<FakeRuntime>()
    private val index = RecordingSessionIndex()
    private val source = FakeSessionSource()
    private lateinit var pool: RuntimePool
    private val factory = FakeEngineFactory()

    private fun TestScope.launcher(
        features: Set<io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId> =
            setOf(CreatesSessions.id, AttachesSessions.id),
        supports: Boolean = true,
    ): Triple<RouteFixture, SessionLauncher, FacadeCapabilities> {
        factory.runtime = { FakeRuntime(it, supports).also { runtime -> runtimes += runtime } }
        val fixture = RouteFixture(this, factory, registration(factory, sources = listOf(source), features = features))
        val enabled = EnabledEngines(fixture.registry, fixture.toggles, backgroundScope)
        lateinit var capabilities: FacadeCapabilities
        val sessions = SessionCatalogService(enabled, index, FakeCursors, fixture.context) { registration, stored ->
            capabilities.stored(registration, stored)
        }
        val registry = ActiveSessionRegistry()
        val policy = SessionPolicy(fixture.routes, enabled, registry, fixture.context)
        val assembler = ActiveSessionAssembler(policy, backgroundScope)
        var handles = 0
        val host = ActiveSessionHost { native, route, model ->
            assembler.assemble(native, route, model, TestHandleScope("h${++handles}", backgroundScope))
        }
        pool = RuntimePool(fixture.context, registry::hasActiveTurn, registry::closeHandles)
        val launcher = SessionLauncher(
            fixture.routes,
            pool,
            sessions,
            host,
            fixture.context,
        )
        capabilities = FacadeCapabilities(sessions, lazyOf(launcher))
        runCurrent()
        return Triple(fixture, launcher, capabilities)
    }

    @Test
    fun `created sessions record Heartbeat provenance and share the pooled runtime`() = runTest {
        val (fixture, launcher, capabilities) = launcher()
        val creator = capabilities.engine(fixture.registry.require(TestEngine)).resolve(CreatesSessions)

        val first = assertIs<FeatureAccess.Available<CreatesSessions>>(
            creator,
        ).feature.create(CreateSessionRequest(fixture.target))
        launcher.create(CreateSessionRequest(fixture.target))

        assertEquals(1, runtimes.size)
        val recorded = index.entries.getValue(first.ref)
        assertEquals(SessionOrigin.Heartbeat, recorded.origin)
        assertEquals(fixture.source.info.id, recorded.lastRoute?.authSource)
        assertEquals(
            ExecutionRoute(TestEngine, fixture.binding.id, fixture.source.info.id, AuthRevision.Known("r1")),
            first.route,
        )
    }

    @Test
    fun `a rotated source retires the old runtime before starting a new one`() = runTest {
        val (fixture, launcher, _) = launcher()
        launcher.create(CreateSessionRequest(fixture.target))
        fixture.sources.remove(fixture.source.info.id)
        fixture.sources.add(managedKey(revision = AuthRevision.Known("r2")))

        launcher.create(CreateSessionRequest(fixture.target))

        assertEquals(2, runtimes.size)
        assertEquals(1, runtimes.first().closes)
        assertEquals(AuthRevision.Known("r2"), runtimes.last().identity.revision)
    }

    @Test
    fun `stored sessions resume through the facade on the requested route only`() = runTest {
        val (fixture, _, capabilities) = launcher()
        val ref = sessionRef("stored")
        val stored = capabilities.stored(fixture.registry.require(TestEngine), FakeStoredSession(summary("stored")))
        val resumer = assertIs<FeatureAccess.Available<ResumesSessions>>(
            stored.features.resolve(ResumesSessions),
        ).feature

        val session: ActiveSession = resumer.resume(ResumeSessionRequest(fixture.target))

        assertEquals(ref, session.ref)
        assertEquals(fixture.source.info.id, index.entries.getValue(ref).lastRoute?.authSource)
        val foreign = fixture.target.copy(engine = EngineId("other"))
        assertEquals(
            InvalidRequest,
            assertFailsWith<EngineException> { resumer.resume(ResumeSessionRequest(foreign)) }.failure,
        )
    }

    @Test
    fun `runtimes without the capability fail explicitly and undeclared engines expose nothing`() = runTest {
        val (fixture, launcher, _) = launcher(supports = false)
        val error = assertFailsWith<EngineException> { launcher.create(CreateSessionRequest(fixture.target)) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability), error.failure)
        assertTrue(index.entries.isEmpty())

        val (bare, _, capabilities) = launcher(features = emptySet())
        val registration = bare.registry.require(TestEngine)
        assertEquals(FeatureAccess.Unsupported, capabilities.engine(registration).resolve(CreatesSessions))
        assertEquals(
            FeatureAccess.Unsupported,
            capabilities.stored(registration, FakeStoredSession(summary("x"))).features.resolve(ResumesSessions),
        )
    }

    @Test
    fun `a runtime with a turn in flight is not retired by a rotated source`() = runTest {
        val (fixture, launcher, _) = launcher()
        val session = launcher.create(CreateSessionRequest(fixture.target))
        val sender = (session.features.resolve(SendsPrompts) as FeatureAccess.Available).feature
        sender.send(PromptRequest(RequestId("r1"), listOf(ContentPart.Text("hi"))))
        fixture.sources.remove(fixture.source.info.id)
        fixture.sources.add(managedKey(revision = AuthRevision.Known("r2")))

        val error = assertFailsWith<EngineException> { launcher.create(CreateSessionRequest(fixture.target)) }

        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
        assertEquals(0, runtimes.single().closes)
    }

    @Test
    fun `idle handles are closed before their rotated runtime stops`() = runTest {
        val (fixture, launcher, _) = launcher()
        val idle = launcher.create(CreateSessionRequest(fixture.target))
        val native = runtimes.single().sessions.single()
        fixture.sources.remove(fixture.source.info.id)
        fixture.sources.add(managedKey(revision = AuthRevision.Known("r2")))

        launcher.create(CreateSessionRequest(fixture.target))
        runCurrent()

        assertEquals(ActiveSessionState.Closed, idle.state.value)
        assertEquals(1, native.closes)
        assertEquals(1, runtimes.first().closes)
    }

    @Test
    fun `the pool refuses new runtimes after profile shutdown`() = runTest {
        val (fixture, launcher, _) = launcher()
        launcher.create(CreateSessionRequest(fixture.target))

        pool.closeAll()
        val error = assertFailsWith<EngineException> { launcher.create(CreateSessionRequest(fixture.target)) }

        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), error.failure)
        assertEquals(1, runtimes.size)
        assertEquals(1, runtimes.single().closes)
    }

    @Test
    fun `a runtime created for a cancelled request is closed not leaked`() = runTest {
        val (fixture, launcher, _) = launcher()
        val gate = CompletableDeferred<Unit>().also { factory.createGate = it }
        val request = launch { launcher.create(CreateSessionRequest(fixture.target)) }
        runCurrent()

        request.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(request.isCancelled)
        assertEquals(1, runtimes.single().closes)

        factory.createGate = null
        launcher.create(CreateSessionRequest(fixture.target))
        assertEquals(2, runtimes.size, "the closed runtime was not kept in the pool")
    }
}

private object FakeCursors : SessionCursors {
    override fun encode(query: SessionQuery, revision: Long, position: IndexPosition) = error("unused")

    override fun decode(cursor: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor, query: SessionQuery) =
        null
}
