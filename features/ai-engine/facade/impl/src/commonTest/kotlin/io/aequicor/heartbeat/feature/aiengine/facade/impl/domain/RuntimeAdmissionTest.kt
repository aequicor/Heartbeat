package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.identity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeAdmissionTest {
    private val identity = RuntimeIdentity(TestEngine, AuthSourceId("source"), AuthRevision.Known("r1"))
    private val request = EngineRuntimeCandidateRequest(identity, LaunchContext())
    private val stamp = EngineCompatibilityStamp(
        TestEngine, "test-host", "test-connector", "1.0.0",
        EngineProtocolIdentity.Negotiated("test-protocol", 1), 1, 1, "test-target", "test-digest",
    )
    private val policy = EngineCompatibilityRequirements(
        TestEngine, setOf(stamp.heartbeat), setOf(stamp.connector), "1.0.0",
        protocols = setOf(stamp.protocol), configurationSchemas = setOf(1), historySchemas = setOf(1),
        nativeTargets = setOf(stamp.nativeTarget), baselineCapabilities = setOf("cancel"), verified = setOf(stamp),
    )
    private var candidate = Candidate()
    private var cleanup: RuntimeCleanupOwner? = null
    private val legacy = FakeEngineFactory()
    private var opens = 0
    private var checks = 0
    private var requirements: EngineCompatibilityRequirements? = policy
    private var open: suspend () -> EngineRuntimeCandidate? = { candidate }
    private var recheck: suspend () -> Unit = { checks++ }
    private val factory = object : EngineFactory by legacy {
        override val runtimeRequirements: EngineCompatibilityRequirements? get() = requirements
        override suspend fun openRuntimeCandidate(
            request: EngineRuntimeCandidateRequest,
            deadline: TimeMark,
        ): EngineRuntimeCandidate? {
            opens++
            assertEquals(this@RuntimeAdmissionTest.request, request)
            return open()
        }
    }

    @Test
    fun `unestablished policy and missing candidate never fall back to legacy runtime`() = runTest {
        requirements = null
        assertIs<RuntimeAdmissionResult.Unavailable>(acquire())
        assertEquals(0, opens)
        requirements = policy
        open = { null }
        assertIs<RuntimeAdmissionResult.Unavailable>(acquire())
        assertEquals(1, opens)
        assertEquals(0, legacy.preparations)
        assertTrue(legacy.createdRuntimes.isEmpty())
    }

    @Test
    fun `factory without restricted candidate refuses without legacy preparation or creation`() = runTest {
        val noCandidate = object : EngineFactory by legacy {
            override val runtimeRequirements = policy
            override suspend fun openRuntimeCandidate(
                request: EngineRuntimeCandidateRequest,
                deadline: TimeMark,
            ): EngineRuntimeCandidate? = null
        }
        val result = RuntimeAdmission(facadeContext(), cleanupOwner()).acquire(
            registration(noCandidate),
            request,
            testScheduler.timeSource.markNow() + 5.seconds,
            recheck,
        )
        assertIs<RuntimeAdmissionResult.Unavailable>(result)
        assertEquals(0, legacy.preparations)
        assertTrue(legacy.createdRuntimes.isEmpty())
    }

    @Test
    fun `foreign policy is rejected before restricted acquisition`() = runTest {
        requirements = policy.copy(engine = EngineId("foreign"))
        assertProtocolFailure { acquire() }
        assertEquals(0, opens)
    }

    @Test
    fun `substituted source or launch request never reaches promotion`() = runTest {
        candidate.input = request.copy(identity = identity.copy(source = AuthSourceId("foreign")))
        assertProtocolFailure { acquire() }
        assertEquals(0, candidate.promotions)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `missing baseline is unknown and positive failure is incompatible without promotion`() = runTest {
        candidate.facts = candidate.facts.copy(isBaselineConfirmed = null)
        val unknown = assertIs<RuntimeAdmissionResult.Rejected>(acquire())
        assertEquals(Compatibility.Unknown, unknown.decision.status)
        candidate = Candidate()
        candidate.facts = candidate.facts.copy(isBaselineConfirmed = false)
        val rejected = assertIs<RuntimeAdmissionResult.Rejected>(acquire())
        assertEquals(Compatibility.Incompatible, rejected.decision.status)
        assertEquals(0, candidate.promotions)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `partial policy and identity cannot admit a candidate`() = runTest {
        requirements = policy.copy(historySchemas = null)
        assertEquals(Compatibility.Unknown, assertIs<RuntimeAdmissionResult.Rejected>(acquire()).decision.status)
        requirements = policy
        candidate = Candidate()
        candidate.facts = candidate.facts.copy(identity = stamp.identity().copy(runtimeFingerprint = null))
        assertEquals(Compatibility.Unknown, assertIs<RuntimeAdmissionResult.Rejected>(acquire()).decision.status)
        assertEquals(0, candidate.promotions)
    }

    @Test
    fun `verified and unverified candidates transfer the checked runtime and close probe resources`() = runTest {
        val verified = assertIs<RuntimeAdmissionResult.Accepted>(acquire())
        assertEquals(stamp, verified.stamp)
        assertEquals(Compatibility.Verified, verified.decision.status)
        assertSame(candidate.runtime, verified.runtime)
        assertEquals(0, candidate.runtime.closes)
        assertEquals(1, candidate.closes)
        assertEquals(4, checks)
        candidate = Candidate()
        candidate.facts = candidate.facts.copy(identity = stamp.copy(runtime = "1.1.0").identity())
        candidate.promotedStamp = stamp.copy(runtime = "1.1.0")
        val unverified = assertIs<RuntimeAdmissionResult.Accepted>(acquire())
        assertEquals("1.1.0", unverified.stamp.runtime)
        assertEquals(Compatibility.Unverified, unverified.decision.status)
        assertEquals(1, candidate.promotions)
    }

    @Test
    fun `rejected candidate cleanup remains under the requested source identity`() = runTest {
        candidate.input = request.copy(identity = identity.copy(source = AuthSourceId("foreign")))
        candidate.beforeClose = { throw EngineException(OperationNotAllowed) }
        assertFailsWith<EngineException> { acquire() }
        assertEquals(1, cleanupOwner().pendingCount)
        candidate.beforeClose = {}
        cleanupOwner().retryFor(identity.engine, identity.source)
        assertEquals(0, cleanupOwner().pendingCount)
        assertEquals(2, candidate.closes)
    }

    @Test
    fun `changed promotion stamp closes the untransferred runtime`() = runTest {
        candidate.promotedStamp = stamp.copy(runtimeFingerprint = "changed")
        assertProtocolFailure { acquire() }
        assertEquals(1, candidate.runtime.closes)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `another runtime credential identity cannot be transferred`() = runTest {
        candidate.runtime = FakeRuntime(identity.copy(revision = AuthRevision.Known("r2")))
        assertProtocolFailure { acquire() }
        assertEquals(1, candidate.runtime.closes)
    }

    @Test
    fun `route revocation after probe cleanup closes runtime before returning`() = runTest {
        recheck = {
            checks++
            if (checks == 4) throw EngineException(OperationNotAllowed)
        }
        assertEquals(OperationNotAllowed, assertFailsWith<EngineException> { acquire() }.failure)
        assertEquals(1, candidate.runtime.closes)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `a hanging final route validation expires and closes the untransferred runtime`() = runTest {
        recheck = {
            checks++
            if (checks == 4) awaitCancellation()
        }
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { acquire() }
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(1, candidate.runtime.closes)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `cancellation during candidate acquisition still closes the returned candidate`() = runTest {
        val gate = CompletableDeferred<Unit>()
        open = {
            withContext(NonCancellable) { gate.await() }
            candidate
        }
        val job = launch { acquire() }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(0, candidate.promotions)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `failed promotion releases candidate and cleanup failure closes a promoted runtime`() = runTest {
        candidate.beforePromotion = { throw EngineException(OperationNotAllowed) }
        assertFailsWith<EngineException> { acquire() }
        assertEquals(1, candidate.closes)
        assertEquals(0, candidate.runtime.closes)
        candidate = Candidate()
        candidate.beforeClose = { throw EngineException(OperationNotAllowed) }
        assertFailsWith<EngineException> { acquire() }
        assertEquals(1, candidate.runtime.closes)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `failed cleanup keeps both resource kinds owned until retry succeeds`() = runTest {
        candidate.beforeClose = { throw EngineException(OperationNotAllowed) }
        candidate.runtime.closeFailure = EngineException(OperationNotAllowed)
        assertFailsWith<EngineException> { acquire() }
        assertEquals(2, cleanupOwner().pendingCount)
        candidate.beforeClose = {}
        candidate.runtime.closeFailure = null
        cleanupOwner().retryAll()
        assertEquals(0, cleanupOwner().pendingCount)
        assertTrue(candidate.runtime.isClosed)
    }

    @Test
    fun `cancellation after native acquisition captures and closes an orphan across dispatcher boundary`() = runTest {
        val gate = CompletableDeferred<Unit>()
        candidate.beforePromotion = { withContext(NonCancellable) { gate.await() } }
        val job = launch { acquire() }
        runCurrent()
        assertEquals(1, candidate.promotions)
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(1, candidate.runtime.closes)
        assertEquals(1, candidate.closes)
    }

    @Test
    fun `expired deadline does not acquire and expiry after baseline never promotes`() = runTest {
        val expired = testScheduler.timeSource.markNow() - 1.seconds
        assertEquals(
            EngineFailure.Transport(TransportFailureReason.Timeout),
            assertFailsWith<EngineException> { acquire(expired) }.failure,
        )
        assertEquals(0, opens)
        open = {
            withContext(NonCancellable) { advanceTimeBy(6.seconds) }
            candidate
        }
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { acquire() }
        assertEquals(0, candidate.promotions)
        assertEquals(1, candidate.closes)
    }

    private fun TestScope.cleanupOwner(): RuntimeCleanupOwner =
        cleanup ?: RuntimeCleanupOwner(facadeContext()).also { cleanup = it }

    private suspend fun TestScope.acquire(
        deadline: TimeMark = testScheduler.timeSource.markNow() + 5.seconds,
    ): RuntimeAdmissionResult = RuntimeAdmission(facadeContext(), cleanupOwner()).acquire(
        registration(factory),
        request,
        deadline,
        recheck,
    )

    private suspend fun assertProtocolFailure(block: suspend () -> Unit) {
        assertEquals(
            EngineFailure.Transport(TransportFailureReason.ProtocolViolation),
            assertFailsWith<EngineException> { block() }.failure,
        )
    }

    private inner class Candidate : EngineRuntimeCandidate {
        var input = this@RuntimeAdmissionTest.request
        var facts = EngineCompatibilityEvidence(stamp.identity(), true, true, setOf("cancel"))
        var runtime = FakeRuntime(identity)
        var promotedStamp = stamp
        var promotions = 0
        var closes = 0
        var beforePromotion: suspend () -> Unit = {}
        var beforeClose: suspend () -> Unit = {}
        override val request get() = input
        override val evidence get() = facts
        override suspend fun promote(expected: EngineCompatibilityStamp, deadline: TimeMark): EngineRuntimePromotion {
            check(promotions == 0 && closes == 0)
            promotions++
            assertEquals(facts.identity, expected.identity())
            beforePromotion()
            runtime.compatibilityStamp = promotedStamp
            return EngineRuntimePromotion(runtime, promotedStamp)
        }

        override suspend fun close() {
            closes++
            beforeClose()
        }
    }
}
