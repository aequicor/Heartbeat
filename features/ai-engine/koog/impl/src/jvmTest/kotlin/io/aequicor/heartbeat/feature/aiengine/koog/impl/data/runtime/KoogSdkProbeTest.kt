package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.aequicor.heartbeat.core.network.OfflineHttpSession
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KoogSdkProbeTest {
    @BeforeTest
    fun enableLog() {
        Log.init(isDebug = true)
    }

    @AfterTest
    fun resetLog() {
        Log.init(isDebug = false)
    }

    @Test
    fun `actual SDK codecs report typed failures and join owned jobs before returning`() = runTest {
        val http = SdkProbeHttpFixture()
        val report = KoogSdkProbe(
            http,
            TestKoogPlatform,
            http.dispatchers,
        ).run(
            TimeSource.Monotonic.markNow() + 10.seconds,
        )
        assertEquals(30, report.receipts.size)
        for (row in report.receipts) {
            assertTrue(row.exchange?.isComplete == true, row.toString())
            assertEquals(true, row.isSdkCleanupConfirmed, row.toString())
            val issue = when {
                row.provider != KoogProvider.Ollama -> null
                row.check == KoogSdkCheck.StreamStop -> KoogSdkIssue.Terminal
                row.check == KoogSdkCheck.MalformedStream -> KoogSdkIssue.MalformedAccepted
                else -> null
            }
            assertEquals(issue, row.issue, row.toString())
            assertEquals(if (issue == null) KoogSdkOutcome.Confirmed else KoogSdkOutcome.Rejected, row.outcome)
        }
        assertEquals(sdkBuildInputs(), report.build)
        assertTrue(report.hasRejectedChecks)
        assertEquals(30, http.sessions.size)
        assertTrue(http.sessions.all { it.client.coroutineContext.job.isCompleted })
        assertTrue(http.sessions.all { it.client.engine.coroutineContext.job.isCompleted })
    }

    @Test
    fun `teardown failure never confirms negative codec checks or cancellation`() = runTest {
        val http = SdkProbeHttpFixture().apply { isSdkCloseFailing = true }
        val report = KoogSdkProbe(http, TestKoogPlatform, http.dispatchers).run(
            TimeSource.Monotonic.markNow() + 10.seconds,
        )
        assertEquals(30, report.receipts.size)
        for (row in report.receipts) {
            assertEquals(KoogSdkOutcome.Rejected, row.outcome, row.toString())
            assertEquals(KoogSdkIssue.Failure, row.issue, row.toString())
        }
        assertTrue(http.sessions.all { it.client.engine.coroutineContext.job.isCompleted })
    }

    @Test
    fun `root force closure cannot conceal SDK job leaks`() = runTest {
        val http = SdkProbeHttpFixture().apply { isLeakingSdkJob = true }
        val report = KoogSdkProbe(
            http,
            TestKoogPlatform,
            http.dispatchers,
        ).run(
            TimeSource.Monotonic.markNow() + 10.seconds,
        )
        assertEquals(30, report.receipts.size)
        assertTrue(report.receipts.all { it.outcome == KoogSdkOutcome.Rejected && it.issue == KoogSdkIssue.OwnedJobs })
        assertTrue(report.receipts.all { it.isSdkCleanupConfirmed == false })
        assertTrue(http.sessions.all { it.client.engine.coroutineContext.job.isCompleted })
    }

    @Test
    fun `expired deadline never allocates a client and remains unobserved`() = runTest {
        val http = SdkProbeHttpFixture()
        val forbidden = object : OfflineHttpClients {
            override suspend fun open(script: List<OfflineHttpExchange>): OfflineHttpSession = error(
                "Unexpected access",
            )
        }
        val report = KoogSdkProbe(forbidden, TestKoogPlatform, http.dispatchers).run(TimeSource.Monotonic.markNow())
        assertEquals(30, report.receipts.size)
        assertTrue(report.receipts.all { it.outcome == KoogSdkOutcome.Unobserved && it.exchange == null })
        assertFalse(report.hasRejectedChecks)
    }

    @Test
    fun `cancellation remains primary when root cleanup also fails`() = runTest {
        val cleanup = IllegalStateException("synthetic cleanup failure")
        val http = SdkProbeHttpFixture().apply { cleanupFailure = cleanup }
        // Hold the HTTP response before headers so cancellation and cleanup collide deterministically.
        val suspended = object : OfflineHttpClients {
            override suspend fun open(script: List<OfflineHttpExchange>): OfflineHttpSession = http.open(
                listOf(
                    script.single().copy(
                        response = OfflineHttpResponse.AwaitCancellation,
                    ),
                ),
            )
        }
        val worker = async {
            KoogSdkProbe(
                suspended,
                TestKoogPlatform,
                http.dispatchers,
            ).run(
                TimeSource.Monotonic.markNow() + 10.seconds,
            )
        }
        http.entered.await()
        worker.cancel(CancellationException("synthetic caller cancellation"))
        val failure = assertFailsWith<CancellationException> { worker.await() }
        assertEquals("synthetic caller cancellation", failure.message)
        assertEquals(1, http.cleanupAttempts.get())
        assertTrue(http.sessions.all { it.client.engine.coroutineContext.job.isCompleted })
    }

    @Test
    fun `nondefault launch cannot be replaced by the embedded SDK`() = runTest {
        val http = SdkProbeHttpFixture()
        val factory = JvmKoogCandidates(http, TestKoogPlatform, http.dispatchers)
        val identity = RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1"))
        val request = EngineRuntimeCandidateRequest(identity, LaunchContext(isInstallCandidate = true))
        assertNull(factory.open(request, TimeSource.Monotonic.markNow() + 10.seconds))
        assertTrue(http.sessions.isEmpty())
    }

    @Test
    fun `even all confirmed SDK rows grant no baseline provenance or promotion`() = runTest {
        for (outcome in KoogSdkOutcome.entries) {
            val request = EngineRuntimeCandidateRequest(
                RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1")),
                LaunchContext(),
            )
            val report = KoogSdkReport(
                sdkBuildInputs(),
                KoogProvider.entries.flatMap { provider ->
                    KoogSdkCheck.entries.map { KoogSdkReceipt(provider, it, outcome) }
                },
            )
            val candidate = SdkOnlyKoogCandidate(request, report)
            assertSame(request, candidate.request)
            assertEquals(
                if (outcome == KoogSdkOutcome.Rejected) false else null,
                candidate.evidence.isBaselineConfirmed,
            )
            assertNull(candidate.evidence.isProvenanceConfirmed)
            assertNull(candidate.evidence.identity.runtimeFingerprint)
            val forged = EngineCompatibilityStamp(
                KoogEngineId, "fictional", "fictional", "0.0.0",
                EngineProtocolIdentity.ProbedSchema("fictional", "fictional"),
                0, 0, "fictional", "fictional",
            )
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val first = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
            assertEquals(EngineFailureReason.CompatibilityUnknown, assertIs<EngineFailure.Engine>(first.failure).reason)
            val repeated = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
            assertEquals(
                AccessFailureReason.OperationNotAllowed,
                assertIs<EngineFailure.Access>(repeated.failure).reason,
            )
            candidate.close()
            candidate.close()
            val closed = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
            assertEquals(AccessFailureReason.OperationNotAllowed, assertIs<EngineFailure.Access>(closed.failure).reason)
        }
    }
}
