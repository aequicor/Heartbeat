package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekHost
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshExternalFingerprint
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshProcessObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class DshCompatibilityTest {
    private val identity = RuntimeIdentity(DeepSeekEngine.Id, AuthSourceId("synthetic"), AuthRevision.Known("1"))

    @Test
    fun `mobile targets have no policy and open no candidate`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            listOf(HostPlatform.Android, HostPlatform.Ios).forEach { host ->
                assertNull(DshJvmCompatibility.requirements(host), host.name)
                assertNull(JvmDshCompatibility(platform(host)).requirements(), host.name)
            }
            assertNull(UnsupportedDshCompatibility.requirements())
            val candidate = fixture.candidates(probe, platform = platform(HostPlatform.Android))
            assertNull(candidate.open(fixture.request(), deadline()) { error("unused") })
            assertNull(UnsupportedDshCandidates.open(fixture.request(), deadline()) { error("unused") })
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `the selected launch is observed through the external fingerprint and the ACP baseline`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            val candidates = fixture.candidates(probe)
            val candidate = assertNotNull(candidates.open(fixture.request(), deadline()) { error("unused") })
            try {
                assertEquals(true, candidate.evidence.isProvenanceConfirmed)
                assertEquals(fixture.fingerprint, candidate.evidence.identity.runtimeFingerprint)
                assertEquals("0.2.1-rc.5", candidate.evidence.identity.runtime)
                assertEquals("darwin-arm64", candidate.evidence.identity.nativeTarget)
                assertEquals(true, candidate.evidence.isBaselineConfirmed)
                assertEquals(1, probe.calls)
                assertEquals(fixture.executable.toString(), probe.lastInstallation?.path)
            } finally {
                withContext(NonCancellable) { candidate.close() }
            }
        }
    }

    @Test
    fun `an expired deadline observes nothing and starts no probe`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            val candidate = assertNotNull(
                fixture.candidates(probe).open(fixture.request(), TimeSource.Monotonic.markNow()) {
                    error("unused")
                },
            )
            try {
                assertEquals(true, candidate.evidence.isProvenanceConfirmed)
                assertNull(candidate.evidence.isBaselineConfirmed)
                assertNull(candidate.evidence.capabilities)
                assertEquals(0, probe.calls)
            } finally {
                withContext(NonCancellable) { candidate.close() }
            }
        }
    }

    @Test
    fun `a missing or script executable leaves the fingerprint unknown and refuses admission`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            val deadline = deadline()

            val missing = fixture.candidates(
                probe,
                configuration = DeepSeekConfiguration(executable = fixture.root.resolve("missing").toString()),
            )
            assertUnknownWithoutProbe(
                assertNotNull(missing.open(fixture.request(), deadline) { error("must not promote") }),
                deadline,
            )

            val script = Files.writeString(fixture.root.resolve("dsh.cmd"), "not a native executable")
            val scripted = fixture.candidates(
                probe,
                configuration = DeepSeekConfiguration(executable = script.toString()),
            )
            assertUnknownWithoutProbe(
                assertNotNull(scripted.open(fixture.request(), deadline) { error("must not promote") }),
                deadline,
            )
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `the candidate promotes the exact stamp once`() = runTest {
        temporary { fixture ->
            val candidates = fixture.candidates(FakeBaselineProbe())
            val deadline = deadline()
            val policy = assertNotNull(DshJvmCompatibility.requirements(HostPlatform.MacOs))
            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(fixture.request(), deadline) { stamp ->
                    promoted += stamp
                    FakeRuntime(identity, stamp, fixture.executable.toString(), observation(fixture))
                },
            )
            val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
            val promotion = candidate.promote(stamp, deadline)
            assertEquals(stamp, promotion.stamp)
            assertEquals(stamp, promotion.runtime.compatibilityStamp)
            assertEquals(identity, promotion.runtime.identity)
            assertEquals(listOf(stamp), promoted)
            val repeated = assertFailsWith<EngineException> { candidate.promote(stamp, deadline) }
            assertEquals(
                AccessFailureReason.OperationNotAllowed,
                assertIs<EngineFailure.Access>(repeated.failure).reason,
            )
            candidate.close()
            candidate.close()
        }
    }

    @Test
    fun `a foreign stamp transfers nothing`() = runTest {
        temporary { fixture ->
            val candidates = fixture.candidates(FakeBaselineProbe())
            val deadline = deadline()
            val policy = assertNotNull(DshJvmCompatibility.requirements(HostPlatform.MacOs))
            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(fixture.request(), deadline) { stamp ->
                    promoted += stamp
                    FakeRuntime(identity, stamp, fixture.executable.toString(), observation(fixture))
                },
            )
            try {
                val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
                val failure = assertFailsWith<EngineException> {
                    candidate.promote(stamp.copy(runtime = "9.9.9"), deadline)
                }
                assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure.failure)
                assertTrue(promoted.isEmpty(), "a foreign stamp must not transfer a runtime")
            } finally {
                withContext(NonCancellable) { candidate.close() }
            }
        }
    }

    @Test
    fun `a promoted runtime that no longer matches the evidence fails closed`() = runTest {
        temporary { fixture ->
            val candidates = fixture.candidates(FakeBaselineProbe())
            val deadline = deadline()
            val policy = assertNotNull(DshJvmCompatibility.requirements(HostPlatform.MacOs))

            assertRejectedPromotion(fixture, policy, deadline, FakeVersionProbe("9.9.9")) { stamp ->
                FakeRuntime(identity, stamp, fixture.executable.toString(), observation(fixture))
            }
            assertRejectedPromotion(fixture, policy, deadline, FakeVersionProbe()) { stamp ->
                FakeRuntime(
                    identity,
                    stamp,
                    fixture.executable.toString(),
                    observation(fixture).copy(protocol = EngineProtocolIdentity.Negotiated("acp", 2)),
                )
            }
            assertRejectedPromotion(fixture, policy, deadline, FakeVersionProbe()) { stamp ->
                FakeRuntime(
                    identity,
                    stamp,
                    fixture.executable.toString(),
                    observation(fixture).copy(selectedExecutable = "/different/dsh"),
                )
            }

            var changed: FakeRuntime? = null
            val candidate = assertNotNull(
                candidates.open(fixture.request(), deadline) { stamp ->
                    FakeRuntime(identity, stamp, fixture.executable.toString(), observation(fixture)).also {
                        changed = it
                    }
                },
            )
            val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
            Files.writeString(fixture.executable, "replaced external fixture")
            val failure = assertFailsWith<EngineException> { candidate.promote(stamp, deadline) }
            assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
            assertTrue(assertNotNull(changed).isClosed)
        }
    }

    private suspend fun assertRejectedPromotion(
        fixture: Fixture,
        policy: EngineCompatibilityRequirements,
        deadline: TimeMark,
        versions: DshRuntimeVersionProbe,
        runtime: (EngineCompatibilityStamp) -> FakeRuntime,
    ) {
        val target = fixture.candidates(FakeBaselineProbe(), versions = versions)
        var created: FakeRuntime? = null
        val candidate = assertNotNull(
            target.open(fixture.request(), deadline) { stamp -> runtime(stamp).also { created = it } },
        )
        try {
            val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
            val failure = assertFailsWith<EngineException> { candidate.promote(stamp, deadline) }
            assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
            assertTrue(assertNotNull(created).isClosed)
        } finally {
            withContext(NonCancellable) { candidate.close() }
        }
    }

    private suspend fun assertUnknownWithoutProbe(candidate: EngineRuntimeCandidate, deadline: TimeMark) {
        assertNull(candidate.evidence.isProvenanceConfirmed)
        assertNull(candidate.evidence.isBaselineConfirmed)
        assertNull(candidate.evidence.identity.runtime)
        assertNull(candidate.evidence.identity.nativeTarget)
        assertNull(candidate.evidence.identity.runtimeFingerprint)
        val failure = assertFailsWith<EngineException> {
            candidate.promote(
                EngineCompatibilityStamp(
                    DeepSeekEngine.Id,
                    "heartbeat",
                    "connector",
                    "0.2.1-rc.5",
                    DshJvmCompatibility.PROTOCOL,
                    0,
                    0,
                    "darwin-arm64",
                    DshExternalFingerprint.PREFIX + "a".repeat(64),
                ),
                deadline,
            )
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
        withContext(NonCancellable) { candidate.close() }
    }

    private fun observation(fixture: Fixture) = DshProcessObservation(
        agentVersion = "0.0.1",
        protocol = EngineProtocolIdentity.Negotiated("acp", 1),
        selectedExecutable = fixture.executable.toString(),
    )

    private fun platform(host: HostPlatform): PlatformInfo = object : PlatformInfo {
        override val host = host
    }

    private fun deadline(): TimeMark = TimeSource.Monotonic.markNow() + 10.seconds

    private suspend fun <T> temporary(block: suspend (Fixture) -> T): T {
        val fixture = Fixture()
        try {
            return block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("dsh-admission-test-")
        val executable: Path = Files.writeString(root.resolve("dsh"), "controlled external fixture")
        val host: DeepSeekHost = DeepSeekHost(
            osName = "Mac OS X",
            osArch = "aarch64",
            path = "",
            userHome = root.toString(),
            localAppData = "",
            isExecutable = { false },
        )
        val platform: PlatformInfo = object : PlatformInfo {
            override val host = HostPlatform.MacOs
        }
        val fingerprint: String = DshExternalFingerprint.PREFIX + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)),
        )

        fun candidates(
            probe: DshBaselineProbe,
            platform: PlatformInfo = this.platform,
            versions: DshRuntimeVersionProbe = FakeVersionProbe(),
            configuration: DeepSeekConfiguration = DeepSeekConfiguration(executable = executable.toString()),
        ): JvmDshCandidates = JvmDshCandidates(
            configuration,
            platform,
            TestDshDispatchers,
            probe,
            versions,
            host,
        )

        fun request(): EngineRuntimeCandidateRequest = EngineRuntimeCandidateRequest(
            RuntimeIdentity(DeepSeekEngine.Id, AuthSourceId("synthetic"), AuthRevision.Known("1")),
            LaunchContext(),
        )

        override fun close() {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private class FakeBaselineProbe : DshBaselineProbe {
        var calls = 0
        var lastInstallation: Installation? = null
        var receipt = DshBaselineReceipt(DshBaselineOutcome.Confirmed, runtimeVersion = "0.2.1-rc.5")

        override suspend fun run(installation: Installation, deadline: TimeMark): DshBaselineReceipt {
            calls++
            lastInstallation = installation
            return receipt
        }
    }

    private class FakeVersionProbe(private val value: String? = "0.2.1-rc.5") : DshRuntimeVersionProbe {
        override suspend fun observe(executable: String, deadline: TimeMark): String? = value
    }

    private object TestDshDispatchers : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private class FakeRuntime(
        override val identity: RuntimeIdentity,
        override val compatibilityStamp: EngineCompatibilityStamp?,
        override val selectedExecutable: String?,
        private val observation: DshProcessObservation,
    ) : DshAdmittedRuntime {
        override val features: EngineFeatures = object : EngineFeatures {
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                FeatureAccess.Unsupported
        }
        override var isClosed: Boolean = false
            private set

        override suspend fun observeOrdinaryStartup(): DshProcessObservation = observation

        override suspend fun close() {
            isClosed = true
        }
    }
}
