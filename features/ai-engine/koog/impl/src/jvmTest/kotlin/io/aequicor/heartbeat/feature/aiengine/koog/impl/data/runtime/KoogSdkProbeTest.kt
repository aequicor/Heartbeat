package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.aequicor.heartbeat.core.network.OfflineHttpSession
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogHistoryFormat
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KoogSdkProbeTest {
    @Test
    fun `native wire fixture matches each actual SDK request`() = runTest {
        for (provider in KoogProvider.entries) {
            val http = SdkProbeHttpFixture()
            val confirmed = KoogNativeProbe(http.dispatchers).runProviderWire(
                TimeSource.Monotonic.markNow() + 10.seconds,
                http,
                TestKoogPlatform,
                provider,
            )
            assertTrue(confirmed, provider.name)
            assertEquals(nativeWireExchange(provider).body, http.lastBody, provider.name)
            assertEquals(nativeWireExchange(provider).url, http.lastUrl, provider.name)
        }
    }

    @Test
    fun `ollama stream rejects invalid decoded fields abnormal finish and data after finish`() = runTest {
        val invalidLines = listOf(
            """{"model":"heartbeat-contract","done":false,"message":{"role":"assistant","content":42}}""",
            """{"model":"heartbeat-contract","done":true,"done_reason":"length"}""",
            """
                |{"model":"heartbeat-contract","done":true,"done_reason":"stop"}
                |{"model":"heartbeat-contract","done":false}
            """.trimMargin(),
        )
        for (body in invalidLines) {
            val http = SdkProbeHttpFixture()
            var exchangeIndex = 0
            val modified = object : OfflineHttpClients {
                override suspend fun open(script: List<OfflineHttpExchange>): OfflineHttpSession {
                    val exchange = script.single()
                    val response = exchange.response
                    val replacement = if (exchangeIndex++ == 1 && response is OfflineHttpResponse.Reply) {
                        exchange.copy(response = response.copy(body = "$body\n"))
                    } else {
                        exchange
                    }
                    return http.open(listOf(replacement))
                }
            }
            val report = KoogSdkProbe(modified, TestKoogPlatform, http.dispatchers).run(
                TimeSource.Monotonic.markNow() + 10.seconds,
                listOf(KoogProvider.Ollama),
            )
            val stream = report.receipts.single { it.check == KoogSdkCheck.StreamStop }
            assertEquals(KoogSdkOutcome.Rejected, stream.outcome)
            assertEquals(KoogSdkIssue.Terminal, stream.issue)
            assertTrue(http.sessions.all { it.client.engine.coroutineContext.job.isCompleted })
        }
    }

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
            val issue = null
            assertEquals(issue, row.issue, row.toString())
            assertEquals(if (issue == null) KoogSdkOutcome.Confirmed else KoogSdkOutcome.Rejected, row.outcome)
        }
        assertEquals(sdkBuildInputs(), report.build)
        assertFalse(report.hasRejectedChecks)
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
        assertNull(factory.open(request, TimeSource.Monotonic.markNow() + 10.seconds) { error("unused") })
        assertTrue(http.sessions.isEmpty())
    }

    @Test
    fun `candidate tests only the selected provider and refuses an unknown provider before IO`() = runTest {
        val http = SdkProbeHttpFixture()
        val factory = JvmKoogCandidates(http, TestKoogPlatform, http.dispatchers)
        val identity = RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1"))
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        assertNull(factory.open(EngineRuntimeCandidateRequest(identity, LaunchContext()), deadline) { error("unused") })
        assertNull(
            factory.open(
                EngineRuntimeCandidateRequest(identity, LaunchContext(), ProviderId("unknown")),
                deadline,
            ) { error("unused") },
        )
        assertTrue(http.sessions.isEmpty())

        val openai = factory.open(
            EngineRuntimeCandidateRequest(identity, LaunchContext(), KoogProvider.OpenAI.id),
            deadline,
        ) { error("unused") }
        assertNotNull(openai)
        assertTrue(openai.evidence.isBaselineConfirmed == true, "confirmed offline baseline")
        assertEquals(KoogJvmCompatibility.baselineCapabilities, openai.evidence.capabilities)
        assertEquals(true, openai.evidence.isProvenanceConfirmed)
        assertEquals(KoogJvmCompatibility.identity(TestKoogPlatform.host), openai.evidence.identity)
        assertEquals(nativeWireExchange(KoogProvider.OpenAI).body, http.lastBody)
        assertEquals(8, http.sessions.size)
        openai.close()

        val ollama = factory.open(
            EngineRuntimeCandidateRequest(identity, LaunchContext(), KoogProvider.Ollama.id),
            deadline,
        ) { error("unused") }
        assertNotNull(ollama)
        assertTrue(ollama.evidence.isBaselineConfirmed == true, "confirmed offline baseline")
        assertEquals(KoogJvmCompatibility.baselineCapabilities, ollama.evidence.capabilities)
        val expectedWire = sdkExchange(KoogProvider.Ollama, KoogSdkCheck.StreamStop)
        assertEquals(expectedWire.body, http.lastBody)
        assertEquals(expectedWire.method, http.lastMethod)
        assertEquals(expectedWire.url, http.lastUrl)
        assertEquals(16, http.sessions.size)
        ollama.close()
    }

    @Test
    fun `a failure of another provider cannot reject the selected route`() {
        val report = KoogSdkReport(
            sdkBuildInputs(),
            listOf(
                KoogSdkReceipt(KoogProvider.OpenAI, KoogSdkCheck.StreamStop, KoogSdkOutcome.Confirmed),
                KoogSdkReceipt(KoogProvider.Ollama, KoogSdkCheck.StreamStop, KoogSdkOutcome.Rejected),
            ),
        )
        val native = KoogNativeReceipt(true, true, true, true, true, true)
        val evidence = KoogJvmCompatibility.evidence(
            TestKoogPlatform.host,
            KoogProvider.OpenAI,
            report,
            native,
            isWireConfirmed = true,
        )
        // The failing row names another provider, so it neither rejects nor completes this baseline.
        assertNull(evidence.isBaselineConfirmed)
        val rejected = KoogJvmCompatibility.evidence(
            TestKoogPlatform.host,
            KoogProvider.Ollama,
            report,
            native,
            isWireConfirmed = true,
        )
        assertEquals(false, rejected.isBaselineConfirmed)
    }

    @Test
    fun `a confirmed baseline admits only the exact assessed stamp once`() = runTest {
        val http = SdkProbeHttpFixture()
        val factory = JvmKoogCandidates(http, TestKoogPlatform, http.dispatchers)
        val identity = RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1"))
        val request = EngineRuntimeCandidateRequest(identity, LaunchContext(), KoogProvider.OpenAI.id)
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        val promoted = mutableListOf<EngineCompatibilityStamp>()
        fun promote(stamp: EngineCompatibilityStamp): EngineRuntime = stubRuntime(identity, stamp)

        val candidate = assertNotNull(
            factory.open(request, deadline) { stamp ->
                promoted += stamp
                promote(stamp)
            },
        )
        val decision = assessEngineCompatibility(KoogJvmCompatibility.requirements, candidate.evidence)
        assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
        assertTrue(decision.isBaselineAllowed)
        val stamp = assertNotNull(decision.stamp)

        val forged = stamp.copy(runtime = "0.0.0")
        val mismatch = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
        assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), mismatch.failure)
        assertTrue(promoted.isEmpty(), "a mismatched stamp must not transfer a runtime")

        val second = assertNotNull(
            factory.open(request, deadline) { value ->
                promoted += value
                promote(value)
            },
        )
        val promotion = second.promote(stamp, deadline)
        assertEquals(stamp, promotion.stamp)
        assertEquals(stamp, promotion.runtime.compatibilityStamp)
        assertEquals(identity, promotion.runtime.identity)
        assertEquals(listOf(stamp), promoted)
        val repeated = assertFailsWith<EngineException> { second.promote(stamp, deadline) }
        assertEquals(AccessFailureReason.OperationNotAllowed, assertIs<EngineFailure.Access>(repeated.failure).reason)
        second.close()
        second.close()
    }

    @Test
    fun `inconclusive and rejected baselines transfer no runtime`() = runTest {
        val request = EngineRuntimeCandidateRequest(
            RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1")),
            LaunchContext(),
            KoogProvider.OpenAI.id,
        )
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        val host = TestKoogPlatform.host
        val native = KoogNativeReceipt(true, true, true, true, true, true)
        val inconclusive = KoogRuntimeCandidate(
            request,
            KoogJvmCompatibility.evidence(
                host = host,
                provider = KoogProvider.OpenAI,
                report = KoogSdkReport(sdkBuildInputs(), emptyList()),
                native = native,
                isWireConfirmed = true,
            ),
        ) { error("an inconclusive baseline must not transfer a runtime") }
        val unknown = assertFailsWith<EngineException> {
            inconclusive.promote(provisionalStamp(), deadline)
        }
        assertEquals(EngineFailureReason.CompatibilityUnknown, assertIs<EngineFailure.Engine>(unknown.failure).reason)

        val rejected = KoogRuntimeCandidate(
            request,
            KoogJvmCompatibility.evidence(
                host,
                KoogProvider.OpenAI,
                KoogSdkReport(sdkBuildInputs(), emptyList()),
                KoogNativeReceipt(true, false, true, true, true, true),
                true,
            ),
        ) { error("a rejected baseline must not transfer a runtime") }
        val failed = assertFailsWith<EngineException> { rejected.promote(provisionalStamp(), deadline) }
        assertEquals(EngineFailureReason.CompatibilityUnknown, assertIs<EngineFailure.Engine>(failed.failure).reason)
    }

    private fun provisionalStamp(): EngineCompatibilityStamp = EngineCompatibilityStamp(
        KoogEngineId,
        "heartbeat",
        "connector",
        "1.3.0",
        KoogJvmCompatibility.protocol,
        KoogJvmCompatibility.CONFIGURATION_REVISION,
        KoogHistoryFormat.CURRENT_REVISION,
        TestKoogPlatform.host.name,
        "dependencies",
    )

    private fun stubRuntime(identity: RuntimeIdentity, stamp: EngineCompatibilityStamp): EngineRuntime =
        object : EngineRuntime {
            override val identity = identity
            override val compatibilityStamp = stamp
            override val features = object : EngineFeatures {
                override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                    FeatureAccess.Unsupported
            }

            override suspend fun close() = Unit
        }
}
