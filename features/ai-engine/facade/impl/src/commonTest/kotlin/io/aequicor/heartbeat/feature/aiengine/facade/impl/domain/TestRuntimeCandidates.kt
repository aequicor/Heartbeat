package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.identity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimePromotion
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark

/** Fictional test contract only; never exported as a policy or baseline for any real connector. */
internal fun testRuntimeStamp(): EngineCompatibilityStamp = EngineCompatibilityStamp(
    TestEngine, "test-host", "test-connector", "1.0.0", EngineProtocolIdentity.Negotiated("test", 1),
    1, 1, "test-target", "test-digest",
)

internal fun testRuntimePolicy(): EngineCompatibilityRequirements {
    val stamp = testRuntimeStamp()
    return EngineCompatibilityRequirements(
        TestEngine, setOf(stamp.heartbeat), setOf(stamp.connector), "1.0.0",
        protocols = setOf(stamp.protocol), configurationSchemas = setOf(1), historySchemas = setOf(1),
        nativeTargets = setOf(stamp.nativeTarget), baselineCapabilities = setOf("cancel"), verified = setOf(stamp),
    )
}

internal fun testRuntimeEvidence(): EngineCompatibilityEvidence =
    EngineCompatibilityEvidence(testRuntimeStamp().identity(), true, true, setOf("cancel"))

internal class TestRuntimeCandidate(
    private val factory: FakeEngineFactory,
    override val request: EngineRuntimeCandidateRequest,
    override val evidence: EngineCompatibilityEvidence,
) : EngineRuntimeCandidate {
    private var isConsumed = false

    override suspend fun promote(expected: EngineCompatibilityStamp, deadline: TimeMark): EngineRuntimePromotion {
        check(!isConsumed)
        isConsumed = true
        factory.promotions++
        factory.prepareRuntime()
        // Simulate the legacy factory's accepted native acquisition, including cancellation-orphan tests.
        val runtime = withContext(NonCancellable) { factory.createRuntime(request.identity) }
        val admitted = if (runtime is FakeRuntime) {
            runtime.compatibilityStamp = expected
            runtime
        } else {
            object : EngineRuntime by runtime {
                override val compatibilityStamp = expected
            }
        }
        return EngineRuntimePromotion(admitted, expected)
    }

    override suspend fun close() {
        isConsumed = true
        factory.candidateCloses++
        factory.closeCandidate()
    }
}
