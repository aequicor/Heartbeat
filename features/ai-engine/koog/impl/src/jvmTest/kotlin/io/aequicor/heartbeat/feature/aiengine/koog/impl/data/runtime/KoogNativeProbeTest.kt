package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KoogNativeProbeTest {
    @Test
    fun `synthetic native session sends resumes reads history and cancels without network`() = runTest {
        val fixture = SdkProbeHttpFixture()
        for (provider in KoogProvider.entries) {
            val receipt = KoogNativeProbe(fixture.dispatchers).run(
                TimeSource.Monotonic.markNow() + 10.seconds,
                provider,
            )
            assertEquals(KoogNativeReceipt(true, true, true, true, true, true), receipt, provider.name)
        }
        assertEquals(emptyList(), fixture.sessions)
    }

    @Test
    fun `native failure rejects baseline but passing checks alone cannot grant it`() {
        val request = EngineRuntimeCandidateRequest(
            RuntimeIdentity(KoogEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1")),
            LaunchContext(),
            KoogProvider.OpenAI.id,
        )
        val sdk = KoogSdkReport(sdkBuildInputs(), emptyList())
        assertNull(
            PartialKoogCandidate(request, sdk, KoogNativeReceipt(true, true, true, true, true, true))
                .evidence.isBaselineConfirmed,
        )
        assertEquals(
            false,
            PartialKoogCandidate(request, sdk, KoogNativeReceipt(true, false, true, true, true, true))
                .evidence.isBaselineConfirmed,
        )
        assertEquals(
            false,
            PartialKoogCandidate(request, sdk, KoogNativeReceipt(true, true, true, false, true, true))
                .evidence.isBaselineConfirmed,
        )
        assertEquals(
            false,
            PartialKoogCandidate(request, sdk, KoogNativeReceipt(true, true, true, true, false, true))
                .evidence.isBaselineConfirmed,
        )
        assertEquals(
            false,
            PartialKoogCandidate(request, sdk, KoogNativeReceipt(true, true, true, true, true, false))
                .evidence.isBaselineConfirmed,
        )
        assertEquals(false, PartialKoogCandidate(request, sdk, isWireConfirmed = false).evidence.isBaselineConfirmed)
    }

    @Test
    fun `expired deadline refuses the probe before a native session is created`() = runTest {
        val fixture = SdkProbeHttpFixture()
        assertFailsWith<TimeoutCancellationException> {
            KoogNativeProbe(fixture.dispatchers).run(TimeSource.Monotonic.markNow() - 1.seconds, KoogProvider.OpenAI)
        }
        assertEquals(emptyList(), fixture.sessions)
    }
}
