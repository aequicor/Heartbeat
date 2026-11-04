package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

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
        val sdk = KoogSdkReport(sdkBuildInputs(), emptyList())
        fun evidence(native: KoogNativeReceipt?, isWireConfirmed: Boolean = true): Boolean? =
            KoogJvmCompatibility.evidence(
                host = TestKoogPlatform.host,
                provider = KoogProvider.OpenAI,
                report = sdk,
                native = native,
                isWireConfirmed = isWireConfirmed,
            ).isBaselineConfirmed
        // Native and wire checks alone leave the SDK rows unobserved: the baseline stays inconclusive.
        assertNull(evidence(KoogNativeReceipt(true, true, true, true, true, true)))
        listOf(
            KoogNativeReceipt(true, false, true, true, true, true),
            KoogNativeReceipt(true, true, true, false, true, true),
            KoogNativeReceipt(true, true, true, true, false, true),
            KoogNativeReceipt(true, true, true, true, true, false),
        ).forEach { assertEquals(false, evidence(it), it.toString()) }
        assertEquals(false, evidence(KoogNativeReceipt(true, true, true, true, true, true), isWireConfirmed = false))
        assertNull(evidence(null))
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
