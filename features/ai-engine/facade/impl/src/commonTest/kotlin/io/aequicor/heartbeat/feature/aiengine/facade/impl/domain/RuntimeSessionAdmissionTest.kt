package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSessionAdmissionTest {
    @Test
    fun `fresh baseline reuses an admitted busy runtime without resetting its start time`() = runTest {
        val f = Fixture(this)
        val first = f.acquire()
        f.isBusy = true
        f.clock.now += 10.seconds
        val started = f.pool.entries.value.single().startedAt
        assertSame(first, f.acquire())
        assertEquals(started, f.pool.entries.value.single().startedAt)
        assertEquals(2, f.factory.candidates)
        assertEquals(f.route.source.scope.provider, f.factory.lastCandidateRequest?.provider)
        assertEquals(1, f.factory.promotions)
        assertEquals(2, f.factory.candidateCloses)
        assertEquals(0, f.runtimes.single().closes)
    }

    @Test
    fun `changed fingerprint with unchanged version is busy and keeps accepted work alive`() = runTest {
        val f = Fixture(this)
        f.acquire()
        f.isBusy = true
        f.changeFingerprint()
        assertEquals(
            EngineFailure.Session(SessionFailureReason.Busy),
            assertFailsWith<EngineException> { f.acquire() }.failure,
        )
        assertEquals(1, f.factory.promotions)
        assertEquals(0, f.runtimes.single().closes)
    }

    @Test
    fun `idle changed artifact closes the previous runtime before promoting replacement`() = runTest {
        val f = Fixture(this)
        val first = f.acquire()
        f.changeFingerprint()
        f.factory.runtime = { identity ->
            assertTrue(f.runtimes.single().isClosed)
            FakeRuntime(identity).also { f.runtimes += it }
        }
        assertNotSame(first, f.acquire())
        assertEquals(2, f.factory.promotions)
        assertEquals(1, f.runtimes.first().closes)
    }

    @Test
    fun `changed source scope with unknown revision never reuses a busy admitted runtime`() = runTest {
        val f = Fixture(this)
        f.changeSourceRevisionToUnknown()
        val first = f.acquire()
        f.isBusy = true
        f.changeSourceOrigin()
        assertEquals(
            EngineFailure.Session(SessionFailureReason.Busy),
            assertFailsWith<EngineException> { f.acquire() }.failure,
        )
        assertEquals(1, f.factory.promotions)
        assertFalse(first.isClosed)
        f.isBusy = false
        assertNotSame(first, f.acquire())
        assertTrue(first.isClosed)
    }

    @Test
    fun `source label change does not retire the admitted runtime`() = runTest {
        val f = Fixture(this)
        val first = f.acquire()
        f.changeSourceLabel()
        assertSame(first, f.acquire())
        assertEquals(1, f.factory.promotions)
    }

    @Test
    fun `metadata runtime cannot gain admission through equal credentials even while busy`() = runTest {
        val f = Fixture(this)
        val metadata = f.pool.runtime(f.route)
        f.isBusy = true
        assertFailsWith<EngineException> { f.acquire() }
        assertEquals(0, f.factory.promotions)
        assertSame(metadata, f.pool.runtime(f.route))
        f.isBusy = false
        assertNotSame(metadata, f.acquire())
        assertTrue(f.runtimes.first().isClosed)
    }

    @Test
    fun `missing policy and failed baseline are distinct and never prepare or promote`() = runTest {
        val f = Fixture(this)
        f.factory.runtimeRequirements = null
        assertEquals(
            EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown),
            assertFailsWith<EngineException> { f.acquire() }.failure,
        )
        assertEquals(0, f.factory.candidates)
        f.factory.runtimeRequirements = testRuntimePolicy()
        f.factory.runtimeEvidence = f.factory.runtimeEvidence.copy(isBaselineConfirmed = false)
        assertEquals(
            EngineFailure.Engine(EngineFailureReason.Incompatible),
            assertFailsWith<EngineException> { f.acquire() }.failure,
        )
        assertEquals(0, f.factory.preparations)
        assertEquals(0, f.prepared)
        assertTrue(f.runtimes.isEmpty())
        f.pool.runtime(f.route)
        assertEquals(1, f.runtimes.size)
    }

    @Test
    fun `launch change during baseline rejects before retirement or binding preparation`() = runTest {
        val f = Fixture(this)
        f.acquire()
        f.factory.beforeCandidate = { f.launch = LaunchContext(LaunchSettings(executable = "/changed")) }
        assertFailsWith<EngineException> { f.acquire() }
        assertEquals(1, f.prepared)
        assertEquals(0, f.runtimes.single().closes)
        assertEquals(1, f.factory.promotions)
    }

    @Test
    fun `route revocation during baseline closes the candidate without creating a runtime`() = runTest {
        val f = Fixture(this)
        f.factory.beforeCandidate = { f.isAllowed = false }
        assertFailsWith<EngineException> { f.acquire() }
        assertEquals(0, f.factory.promotions)
        assertEquals(1, f.factory.candidateCloses)
        assertEquals(0, f.prepared)
    }

    @Test
    fun `failed reuse cleanup never closes the borrowed runtime and retry recovers`() = runTest {
        val f = Fixture(this)
        val first = f.acquire()
        f.isBusy = true
        f.factory.closeCandidate = { throw EngineException(OperationNotAllowed) }
        assertFailsWith<EngineException> { f.acquire() }
        assertEquals(0, f.runtimes.single().closes)
        f.factory.closeCandidate = {}
        assertSame(first, f.acquire())
        assertEquals(1, f.factory.promotions)
    }

    @Test
    fun `shutdown during baseline waits for candidate cleanup and prevents promotion`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.factory.beforeCandidate = { gate.await() }
        val request = async { assertFailsWith<EngineException> { f.acquire() } }
        runCurrent()
        val shutdown = async { f.pool.closeAll() }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        gate.complete(Unit)
        request.await()
        shutdown.await()
        assertEquals(0, f.factory.promotions)
        assertEquals(1, f.factory.candidateCloses)
    }

    @Test
    fun `source lock waiting consumes the absolute admission deadline`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.factory.beforeCandidate = { gate.await() }
        val first = launch { f.acquire(testScheduler.timeSource.markNow() + 10.seconds) }
        runCurrent()
        val second = async { assertFailsWith<TimeoutCancellationException> { f.acquire() } }
        advanceTimeBy(5.seconds)
        runCurrent()
        second.await()
        assertEquals(1, f.factory.candidates)
        first.cancel()
        first.join()
    }

    @Test
    fun `route assessment observes the exact provider and never starts or promotes a runtime`() = runTest {
        val f = Fixture(this)
        val result = assertIs<RouteAdmission.Assessed>(f.assess())
        assertEquals(Compatibility.Verified, result.decision.status)
        assertEquals(1, f.factory.candidates)
        assertEquals(f.route.source.scope.provider, f.factory.lastCandidateRequest?.provider)
        assertEquals(0, f.factory.promotions)
        assertEquals(1, f.factory.candidateCloses)
        assertTrue(f.runtimes.isEmpty())
        assertEquals(0, f.prepared)
    }

    @Test
    fun `route assessment surfaces a refused baseline and closes its candidate`() = runTest {
        val f = Fixture(this)
        f.factory.runtimeEvidence = f.factory.runtimeEvidence.copy(isBaselineConfirmed = false)
        assertEquals(Compatibility.Incompatible, assertIs<RouteAdmission.Assessed>(f.assess()).decision.status)
        assertEquals(0, f.factory.promotions)
        assertEquals(1, f.factory.candidateCloses)
        assertTrue(f.runtimes.isEmpty())
    }

    @Test
    fun `route assessment without a policy is unavailable and creates nothing`() = runTest {
        val f = Fixture(this)
        f.factory.runtimeRequirements = null
        assertEquals(RouteAdmission.Unavailable, f.assess())
        assertEquals(0, f.factory.candidates)
        assertTrue(f.runtimes.isEmpty())
    }

    @Test
    fun `route assessment is refused after revocation without starting a runtime`() = runTest {
        val f = Fixture(this)
        f.isAllowed = false
        assertFailsWith<EngineException> { f.assess() }
        assertEquals(0, f.factory.candidates)
        assertEquals(0, f.factory.promotions)
        assertTrue(f.runtimes.isEmpty())
    }

    private class Fixture(val scope: TestScope) {
        val factory = FakeEngineFactory()
        val runtimes = mutableListOf<FakeRuntime>()
        val clock = FixedClock()
        var isBusy = false
        var isAllowed = true
        var launch = LaunchContext()
        var prepared = 0
        private var source = managedKey()
        private val binding = EngineBinding(EngineBindingId("binding"), TestEngine, source.info.id)
        private val registration = registration(factory)
        val route: ResolvedRoute
            get() = ResolvedRoute(registration, binding, source, EngineContext(TestEngine, binding.id))
        val pool = RuntimePool(scope.facadeContext(clock), { _, _ -> isBusy }, { _, _ -> }, { launch })

        init {
            factory.runtime = { identity -> FakeRuntime(identity).also { runtimes += it } }
        }

        suspend fun acquire(deadline: TimeMark = scope.testScheduler.timeSource.markNow() + 5.seconds) =
            pool.sessionRuntime(
                route,
                deadline,
                recheck = { if (!isAllowed) throw EngineException(OperationNotAllowed) },
                prepare = { prepared++ },
            )

        suspend fun assess(deadline: TimeMark = scope.testScheduler.timeSource.markNow() + 5.seconds) =
            pool.assessAdmission(
                route,
                deadline,
                recheck = { if (!isAllowed) throw EngineException(OperationNotAllowed) },
            )

        fun changeFingerprint() {
            val evidence = factory.runtimeEvidence
            factory.runtimeEvidence = evidence.copy(identity = evidence.identity.copy(runtimeFingerprint = "changed"))
        }

        fun changeSourceRevisionToUnknown() {
            source = source.copy(info = source.info.copy(revision = AuthRevision.Unknown))
        }

        fun changeSourceOrigin() {
            source = source.copy(scope = source.scope.copy(origin = EndpointOrigin("https://other.example.com")))
        }

        fun changeSourceLabel() {
            source = source.copy(info = source.info.copy(label = "renamed"))
        }
    }
}
