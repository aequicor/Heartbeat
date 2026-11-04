package io.aequicor.heartbeat.feature.aiengine.pi.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiArtifactFixture
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeAdmission
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeDistribution
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.generated.PiBuildContract
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.piArchive
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.piDispatchers
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.piTemporary
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.probeCommand
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class PiCompatibilityTest {
    private val identity = RuntimeIdentity(PiEngineId, AuthSourceId("synthetic"), AuthRevision.Known("1"))

    @Test
    fun `the desktop policy is complete, unverified and admits the assessed baseline`() = runTest {
        piTemporary { root ->
            val distribution = distribution(root, piDispatchers())
            val policy = assertNotNull(JvmPiCompatibility(distribution, platform(HostPlatform.MacOs)).requirements())
            assertEquals(PiEngineId, policy.engine)
            assertEquals(setOf(PiBuildContract.heartbeat), policy.heartbeatVersions)
            assertEquals(setOf(PiBuildContract.connector), policy.connectorVersions)
            assertEquals(PiBuildContract.runtimeVersion, policy.minimumRuntime)
            assertEquals(setOf(EngineProtocolIdentity.ProbedSchema("pi-rpc", "1")), policy.protocols)
            assertEquals(setOf(PiConfigurationFormat.CURRENT_REVISION), policy.configurationSchemas)
            assertEquals(setOf(PiHistoryFormat.CURRENT_REVISION), policy.historySchemas)
            assertEquals(setOf("darwin-arm64"), policy.nativeTargets)
            assertEquals(PiJvmCompatibility.baselineCapabilities, policy.baselineCapabilities)
            assertTrue(policy.verified.isEmpty(), "verified must stay empty")

            val evidence = PiJvmCompatibility.evidence(
                PiArtifactFacts("0.87.1", "darwin-arm64", "a".repeat(64)),
                PiBaselineReceipt(PiBaselineOutcome.Confirmed, PiBaselineOutcome.Confirmed),
                isProvenanceConfirmed = true,
            )
            val decision = assessEngineCompatibility(policy, evidence)
            assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
            assertTrue(decision.isBaselineAllowed)
            assertTrue(!decision.isAutomaticUpdateAllowed)
            assertEquals(setOf("rpc.launch", "catalog.models"), evidence.capabilities)
        }
    }

    @Test
    fun `mobile and unpinned hosts have no policy at all`() = runTest {
        piTemporary { root ->
            val distribution = distribution(root, piDispatchers())
            listOf(HostPlatform.Android, HostPlatform.Ios).forEach { host ->
                assertNull(JvmPiCompatibility(distribution, platform(host)).requirements(), host.name)
            }
            assertNull(PiJvmCompatibility.requirements(null, HostPlatform.MacOs))
            assertNull(UnsupportedPiCompatibility.requirements())
        }
    }

    @Test
    fun `unobserved checks stay inconclusive and a conclusive failure rejects the baseline`() {
        val confirmed = PiJvmCompatibility.evidence(
            PiArtifactFacts("0.87.1", "darwin-arm64", "a".repeat(64)),
            PiBaselineReceipt(PiBaselineOutcome.Confirmed, PiBaselineOutcome.Confirmed),
            isProvenanceConfirmed = true,
        )
        assertEquals(true, confirmed.isBaselineConfirmed)
        assertEquals(PiJvmCompatibility.baselineCapabilities, confirmed.capabilities)

        val unobserved = PiJvmCompatibility.evidence(
            PiArtifactFacts("0.87.1", "darwin-arm64", "a".repeat(64)),
            PiBaselineReceipt(PiBaselineOutcome.Confirmed, PiBaselineOutcome.Unobserved),
            isProvenanceConfirmed = true,
        )
        assertNull(unobserved.isBaselineConfirmed)
        assertNull(unobserved.capabilities)

        val rejected = PiJvmCompatibility.evidence(
            PiArtifactFacts("0.87.1", "darwin-arm64", "a".repeat(64)),
            PiBaselineReceipt(PiBaselineOutcome.Confirmed, PiBaselineOutcome.Rejected),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, rejected.isBaselineConfirmed)
        assertNull(rejected.capabilities)

        val unknownProvenance = PiJvmCompatibility.evidence(
            PiArtifactFacts("0.87.1", "darwin-arm64", null),
            baseline = null,
            isProvenanceConfirmed = null,
        )
        assertNull(unknownProvenance.isBaselineConfirmed)
        assertNull(unknownProvenance.isProvenanceConfirmed)
        assertNull(unknownProvenance.identity.runtimeFingerprint)
    }

    @Test
    fun `the bundled candidate verifies the pinned inventory, then promotes the exact stamp once`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), cache.release, cache, piDispatchers())
            val policy = assertNotNull(JvmPiCompatibility(distribution, platform(HostPlatform.MacOs)).requirements())
            val probe = FakeBaselineProbe()
            val candidates = JvmPiCandidates(distribution, platform(HostPlatform.MacOs), piDispatchers(), probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val request = EngineRuntimeCandidateRequest(
                identity,
                LaunchContext(LaunchSettings(runtime = RuntimePreference.Bundled)),
            )

            val first = assertNotNull(candidates.open(request, deadline) { _, _ -> error("unused") })
            try {
                val decision = assessEngineCompatibility(policy, first.evidence)
                assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
                val stamp = assertNotNull(decision.stamp)
                assertEquals(cache.release.version, first.evidence.identity.runtime)
                assertEquals(cache.release.target, first.evidence.identity.nativeTarget)
                assertEquals(cache.release.artifact.download.sha256, first.evidence.identity.runtimeFingerprint)
                assertEquals(true, first.evidence.isProvenanceConfirmed)
                assertEquals(1, probe.calls)
                assertTrue(Files.isExecutable(assertNotNull(probe.lastExecutable)))

                val forged = stamp.copy(runtime = "0.0.0")
                val mismatch = assertFailsWith<EngineException> { first.promote(forged, deadline) }
                assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), mismatch.failure)
            } finally {
                withContext(NonCancellable) { first.close() }
            }

            val promoted = mutableListOf<EngineCompatibilityStamp>()
            var promotedAdmission: PiRuntimeAdmission? = null
            val second = assertNotNull(
                candidates.open(request, deadline) { admission, value ->
                    promoted += value
                    promotedAdmission = admission
                    TestRuntime(identity, value, admission)
                },
            )
            val decision = assessEngineCompatibility(policy, second.evidence)
            val stamp = assertNotNull(decision.stamp)
            val promotion = second.promote(stamp, deadline)
            assertEquals(stamp, promotion.stamp)
            assertEquals(stamp, promotion.runtime.compatibilityStamp)
            assertEquals(identity, promotion.runtime.identity)
            assertEquals(listOf(stamp), promoted)
            // The callback receives the executable the baseline hashed, not a launch resolved at promotion time.
            val admission = assertNotNull(promotedAdmission)
            assertEquals(admission.executable, admission.startup.executable)
            assertEquals(admission.executable, assertNotNull(probe.lastExecutable))
            assertEquals(stamp.runtimeFingerprint, second.evidence.identity.runtimeFingerprint)
            val repeated = assertFailsWith<EngineException> { second.promote(stamp, deadline) }
            assertEquals(
                AccessFailureReason.OperationNotAllowed,
                assertIs<EngineFailure.Access>(repeated.failure).reason,
            )
            second.close()
            second.close()
        }
    }

    @Test
    fun `a promotion whose runtime reports another executable or version fails closed`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), cache.release, cache, piDispatchers())
            val policy = assertNotNull(JvmPiCompatibility(distribution, platform(HostPlatform.MacOs)).requirements())
            val probe = FakeBaselineProbe()
            val candidates = JvmPiCandidates(distribution, platform(HostPlatform.MacOs), piDispatchers(), probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val request = EngineRuntimeCandidateRequest(
                identity,
                LaunchContext(LaunchSettings(runtime = RuntimePreference.Bundled)),
            )

            var changedExecutable: TestRuntime? = null
            val executableCandidate = assertNotNull(
                candidates.open(request, deadline) { admission, stamp ->
                    TestRuntime(
                        identity,
                        stamp,
                        admission,
                        executable = admission?.executable?.resolveSibling("another-pi"),
                    ).also { changedExecutable = it }
                },
            )
            val executableStamp = assertNotNull(
                assessEngineCompatibility(policy, executableCandidate.evidence).stamp,
            )
            val executableFailure = assertFailsWith<EngineException> {
                executableCandidate.promote(executableStamp, deadline)
            }
            assertEquals(
                EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown),
                executableFailure.failure,
            )
            // The rejected runtime is closed and no stamp is handed to the facade.
            assertTrue(assertNotNull(changedExecutable).isClosed)
            executableCandidate.close()

            var changedVersion: TestRuntime? = null
            val versionCandidate = assertNotNull(
                candidates.open(request, deadline) { admission, stamp ->
                    TestRuntime(identity, stamp, admission, version = "0.0.0").also { changedVersion = it }
                },
            )
            val versionStamp = assertNotNull(assessEngineCompatibility(policy, versionCandidate.evidence).stamp)
            val versionFailure = assertFailsWith<EngineException> {
                versionCandidate.promote(versionStamp, deadline)
            }
            assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), versionFailure.failure)
            assertTrue(assertNotNull(changedVersion).isClosed)
            versionCandidate.close()
        }
    }

    @Test
    fun `the promote callback receives the verified executable instead of a re-resolved launch`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), cache.release, cache, piDispatchers())
            val executable = Files.writeString(root.resolve("pi"), "binary")
            assertTrue(executable.toFile().setExecutable(true))
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)),
            )
            val probe = FakeBaselineProbe()
            val candidates = JvmPiCandidates(distribution, platform(HostPlatform.MacOs), piDispatchers(), probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val request = launch(executable, digest = digest)

            var received: PiRuntimeAdmission? = null
            val candidate = assertNotNull(
                candidates.open(request, deadline) { admission, stamp ->
                    received = admission
                    TestRuntime(identity, stamp, admission)
                },
            )
            val policy = assertNotNull(JvmPiCompatibility(distribution, platform(HostPlatform.MacOs)).requirements())
            val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
            candidate.promote(stamp, deadline).runtime.close()

            val admission = assertNotNull(received)
            // This is the executable the baseline hashed and the managed record pins, not a second resolution.
            assertEquals(executable, admission.executable)
            assertEquals(executable, admission.startup.executable)
            assertEquals(request.launch.managed, admission.managed)
            assertEquals(executable, probe.lastExecutable)
            assertEquals(1, probe.calls)
            candidate.close()
        }
    }

    @Test
    fun `a managed copy that differs from its recorded executable digest is rejected before any probe`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), cache.release, cache, piDispatchers())
            val executable = Files.writeString(root.resolve("pi"), "binary")
            assertTrue(executable.toFile().setExecutable(true))
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)),
            )
            val probe = FakeBaselineProbe()
            val candidates = JvmPiCandidates(distribution, platform(HostPlatform.MacOs), piDispatchers(), probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds

            val mismatched = launch(executable, digest = "0".repeat(64))
            val rejected = assertNotNull(candidates.open(mismatched, deadline) { _, _ -> error("unused") })
            assertEquals(false, rejected.evidence.isProvenanceConfirmed)
            assertNull(rejected.evidence.isBaselineConfirmed)
            assertNull(rejected.evidence.identity.runtimeFingerprint)
            assertEquals(0, probe.calls)
            rejected.close()

            val unrecorded = launch(executable, digest = null)
            val unknown = assertNotNull(candidates.open(unrecorded, deadline) { _, _ -> error("unused") })
            assertNull(unknown.evidence.isProvenanceConfirmed)
            assertNull(unknown.evidence.isBaselineConfirmed)
            assertEquals(0, probe.calls)
            unknown.close()

            // The record's release archive digest, even when it equals the file's digest, is not executable provenance.
            val archiveOnly = EngineRuntimeCandidateRequest(
                identity,
                LaunchContext(
                    LaunchSettings(runtime = RuntimePreference.Managed),
                    ManagedInstall("0.87.1", executable.toString(), Instant.fromEpochSeconds(0), sha256 = digest),
                ),
            )
            val unknownProvenance = assertNotNull(candidates.open(archiveOnly, deadline) { _, _ -> error("unused") })
            assertNull(unknownProvenance.evidence.isProvenanceConfirmed)
            assertNull(unknownProvenance.evidence.isBaselineConfirmed)
            assertNull(unknownProvenance.evidence.identity.runtimeFingerprint)
            assertEquals(0, probe.calls)
            unknownProvenance.close()

            val verified = assertNotNull(
                candidates.open(launch(executable, digest = digest), deadline) { _, _ -> error("unused") },
            )
            assertEquals(true, verified.evidence.isProvenanceConfirmed)
            assertEquals(true, verified.evidence.isBaselineConfirmed)
            assertEquals(digest, verified.evidence.identity.runtimeFingerprint)
            assertEquals(1, probe.calls)
            assertEquals(executable, probe.lastExecutable)
            verified.close()
        }
    }

    @Test
    fun `a custom executable has no pinned identity and transfers nothing`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), cache.release, cache, piDispatchers())
            val executable = Files.writeString(root.resolve("pi"), "binary")
            val probe = FakeBaselineProbe()
            val candidates = JvmPiCandidates(distribution, platform(HostPlatform.MacOs), piDispatchers(), probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val request = EngineRuntimeCandidateRequest(
                identity,
                LaunchContext(LaunchSettings(executable = executable.toString())),
            )

            val candidate = assertNotNull(candidates.open(request, deadline) { _, _ -> error("unused") })
            try {
                assertNull(candidate.evidence.isProvenanceConfirmed)
                assertNull(candidate.evidence.isBaselineConfirmed)
                assertNull(candidate.evidence.identity.runtime)
                assertNull(candidate.evidence.identity.runtimeFingerprint)
                assertEquals(0, probe.calls)
                val failure = assertFailsWith<EngineException> {
                    candidate.promote(
                        EngineCompatibilityStamp(
                            PiEngineId, "heartbeat", "connector", "0.87.1",
                            PiJvmCompatibility.protocol, 0, 0, "darwin-arm64", "digest",
                        ),
                        deadline,
                    )
                }
                assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
                assertEquals(0, probe.calls)
            } finally {
                withContext(NonCancellable) { candidate.close() }
            }
        }
    }

    @Test
    fun `the baseline command is the existing socket-free catalog probe`() {
        assertEquals(
            listOf(
                Path.of("/data/pi").toString(),
                "--mode",
                "rpc",
                "--no-session",
                "--no-extensions",
                "--no-skills",
                "--no-prompt-templates",
                "--no-context-files",
                "--no-themes",
            ),
            probeCommand(Path.of("/data/pi")),
        )
    }

    @Test
    fun `an expired deadline observes nothing and starts no process`() = runTest {
        val probe = JvmPiBaselineProbe(piDispatchers())
        val receipt = probe.run(Path.of("/nonexistent/pi"), TimeSource.Monotonic.markNow())
        assertEquals(PiBaselineOutcome.Unobserved, receipt.launch)
        assertEquals(PiBaselineOutcome.Unobserved, receipt.catalog)
        assertTrue(!receipt.hasRejectedCheck)
    }

    private fun platform(host: HostPlatform): PlatformInfo = object : PlatformInfo {
        override val host = host
    }

    private fun distribution(root: Path, dispatchers: DispatcherProvider): PiRuntimeDistribution {
        val cache = PiArtifactFixture(piArchive(root))
        return PiRuntimeDistribution(root.resolve("cache"), cache.release, cache, dispatchers)
    }

    private fun launch(executable: Path, digest: String?): EngineRuntimeCandidateRequest =
        EngineRuntimeCandidateRequest(
            identity,
            LaunchContext(
                LaunchSettings(runtime = RuntimePreference.Managed),
                ManagedInstall("0.87.1", executable.toString(), Instant.fromEpochSeconds(0), executableSha256 = digest),
            ),
        )

    private class FakeBaselineProbe : PiBaselineProbe {
        var calls = 0
        var lastExecutable: Path? = null
        var receipt = PiBaselineReceipt(PiBaselineOutcome.Confirmed, PiBaselineOutcome.Confirmed)

        override suspend fun run(executable: Path, deadline: TimeMark): PiBaselineReceipt {
            calls++
            lastExecutable = executable
            return receipt
        }
    }

    private class TestRuntime(
        override val identity: RuntimeIdentity,
        override val compatibilityStamp: EngineCompatibilityStamp,
        private val admission: PiRuntimeAdmission? = null,
        private val executable: Path? = admission?.executable,
        private val version: String? = admission?.startup?.runtimeVersion,
    ) : EngineRuntime,
        PiAdmittedRuntime {
        override val selectedExecutable: Path? get() = executable
        override val admittedVersion: String? get() = version

        @Volatile
        override var isClosed: Boolean = false
            private set

        override val features: EngineFeatures = object : EngineFeatures {
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                FeatureAccess.Unsupported
        }

        override suspend fun close() {
            isClosed = true
        }
    }
}
