package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KIMI_RUNTIME_VERSION
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KIMI_SOURCE_COMMIT
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiControlledRuntime
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiRuntimeSetup
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.NoManagedKimiRuntime
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.TestKimiDispatchers
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.generated.KimiBuildContract
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.kimiRuntimeTarget
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class KimiCompatibilityTest {
    private val identity = RuntimeIdentity(KimiEngine.Id, AuthSourceId("synthetic"), AuthRevision.Known("1"))

    @Test
    fun `the desktop policy is complete, unverified and admits the assessed baseline`() {
        val policy = assertNotNull(KimiJvmCompatibility.requirements(HostPlatform.MacOs))
        assertEquals(KimiEngine.Id, policy.engine)
        assertEquals(setOf(KimiBuildContract.heartbeat), policy.heartbeatVersions)
        assertEquals(setOf(KimiBuildContract.connector), policy.connectorVersions)
        assertEquals(KIMI_RUNTIME_VERSION, policy.minimumRuntime)
        assertEquals("2.4.0-hb7", policy.minimumRuntime)
        assertNull(policy.maximumRuntimeExclusive, "the pin must not invent an upper bound")
        assertEquals(setOf(EngineProtocolIdentity.Negotiated("acp", 1)), policy.protocols)
        assertEquals(setOf(0), policy.configurationSchemas)
        assertEquals(setOf(0), policy.historySchemas)
        assertEquals(
            setOf("win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64", "linux-x64"),
            policy.nativeTargets,
        )
        assertEquals(KimiJvmCompatibility.baselineCapabilities, policy.baselineCapabilities)
        assertTrue(policy.verified.isEmpty(), "verified must stay empty")

        val evidence = KimiJvmCompatibility.evidence(
            KimiArtifactFacts("2.4.0-hb7", "darwin-arm64", "a".repeat(64)),
            baseline(),
            isProvenanceConfirmed = true,
        )
        val decision = assessEngineCompatibility(policy, evidence)
        assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
        assertTrue(decision.isBaselineAllowed)
        assertFalse(decision.isAutomaticUpdateAllowed)
        assertEquals(
            setOf("acp.initialize", "heartbeat.permissionGate.1", "heartbeat.hostedCallBinding.1"),
            evidence.capabilities,
        )
    }

    @Test
    fun `mobile targets have no policy and open no candidate`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            listOf(HostPlatform.Android, HostPlatform.Ios).forEach { host ->
                assertNull(KimiJvmCompatibility.requirements(host), host.name)
                assertNull(JvmKimiCompatibility(platform(host)).requirements(), host.name)
            }
            assertNull(UnsupportedKimiCompatibility.requirements())
            val candidate = JvmKimiCandidates(
                fixture.setup(),
                platform(HostPlatform.Android),
                TestKimiDispatchers,
                probe,
            ) { true }
            assertNull(candidate.open(fixture.request(), deadline()) { error("unused") })
            assertNull(
                UnsupportedKimiCandidates.open(fixture.request(), deadline()) { error("unused") },
            )
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `unobserved checks stay inconclusive and a conclusive failure rejects the baseline`() {
        val facts = KimiArtifactFacts("2.4.0-hb7", "darwin-arm64", "a".repeat(64))
        val confirmed = KimiJvmCompatibility.evidence(facts, baseline(), isProvenanceConfirmed = true)
        assertEquals(true, confirmed.isBaselineConfirmed)
        assertEquals(KimiJvmCompatibility.baselineCapabilities, confirmed.capabilities)

        val unobserved = KimiJvmCompatibility.evidence(
            facts,
            baseline(hostedCallBinding = KimiBaselineOutcome.Unobserved),
            isProvenanceConfirmed = true,
        )
        assertNull(unobserved.isBaselineConfirmed)
        assertNull(unobserved.capabilities)

        val rejected = KimiJvmCompatibility.evidence(
            facts,
            baseline(permissionGate = KimiBaselineOutcome.Rejected),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, rejected.isBaselineConfirmed)
        assertNull(rejected.capabilities)

        val failedHandshake = KimiJvmCompatibility.evidence(
            facts,
            baseline(initialize = KimiBaselineOutcome.Rejected),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, failedHandshake.isBaselineConfirmed)
        assertNull(failedHandshake.capabilities)

        val unknownFacts = KimiArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null)
        val unknown = KimiJvmCompatibility.evidence(unknownFacts, baseline = null, isProvenanceConfirmed = null)
        assertNull(unknown.isBaselineConfirmed)
        assertNull(unknown.isProvenanceConfirmed)
        assertNull(unknown.capabilities)
        assertNull(unknown.identity.runtimeFingerprint)
    }

    @Test
    fun `an expired deadline observes nothing and starts no probe`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            val candidate = assertNotNull(
                fixture.candidates(probe).open(
                    fixture.request(recorded = fixture.digest),
                    TimeSource.Monotonic.markNow(),
                ) { error("unused") },
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
    fun `the selected managed copy is observed only through the pinned release digest and its store record`() =
        runTest {
            temporary { fixture ->
                val probe = FakeBaselineProbe()
                val candidates = fixture.candidates(probe)
                val deadline = deadline()

                val matched = assertNotNull(
                    candidates.open(fixture.request(recorded = fixture.digest), deadline) { error("unused") },
                )
                try {
                    assertEquals(true, matched.evidence.isProvenanceConfirmed)
                    assertEquals(true, matched.evidence.isBaselineConfirmed)
                    assertEquals(KIMI_RUNTIME_VERSION, matched.evidence.identity.runtime)
                    assertEquals(fixture.target, matched.evidence.identity.nativeTarget)
                    assertEquals(fixture.digest, matched.evidence.identity.runtimeFingerprint)
                    assertEquals(1, probe.calls)
                    assertEquals(fixture.executable.toString(), probe.lastInstallation?.path)
                } finally {
                    withContext(NonCancellable) { matched.close() }
                }

                // Only the published pin is recorded: the selected bytes are still bound to its digest.
                val pinnedOnly = assertNotNull(
                    candidates.open(fixture.request(recorded = null), deadline) { error("unused") },
                )
                try {
                    assertEquals(true, pinnedOnly.evidence.isProvenanceConfirmed)
                    assertEquals(true, pinnedOnly.evidence.isBaselineConfirmed)
                    assertEquals(fixture.digest, pinnedOnly.evidence.identity.runtimeFingerprint)
                    assertEquals(2, probe.calls)
                } finally {
                    withContext(NonCancellable) { pinnedOnly.close() }
                }

                val mismatched = assertNotNull(
                    candidates.open(fixture.request(recorded = "0".repeat(64)), deadline) { error("unused") },
                )
                try {
                    assertEquals(false, mismatched.evidence.isProvenanceConfirmed)
                    assertNull(mismatched.evidence.isBaselineConfirmed)
                    assertNull(mismatched.evidence.identity.runtime)
                    assertNull(mismatched.evidence.identity.runtimeFingerprint)
                    assertEquals(2, probe.calls, "a mismatching record must not run the baseline")
                } finally {
                    withContext(NonCancellable) { mismatched.close() }
                }

                val noPin = fixture.candidates(probe) { false }
                val unpinned = assertNotNull(
                    noPin.open(fixture.request(recorded = fixture.digest), deadline) { error("unused") },
                )
                try {
                    assertNull(unpinned.evidence.isProvenanceConfirmed)
                    assertNull(unpinned.evidence.isBaselineConfirmed)
                    assertNull(unpinned.evidence.identity.runtimeFingerprint)
                    assertEquals(2, probe.calls)
                } finally {
                    withContext(NonCancellable) { unpinned.close() }
                }
            }
        }

    @Test
    fun `a custom or system copy without the pin has no identity and is refused`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            val candidates = fixture.candidates(probe)
            val deadline = deadline()

            val custom = assertNotNull(
                candidates.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(LaunchSettings(executable = fixture.executable.toString())),
                    ),
                    deadline,
                ) { error("must not promote") },
            )
            assertUnknownWithoutProbe(custom, deadline)

            val system = assertNotNull(
                candidates.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(LaunchSettings(runtime = RuntimePreference.System)),
                    ),
                    deadline,
                ) { error("must not promote") },
            )
            assertUnknownWithoutProbe(system, deadline)

            // A retained record of another version cannot replace the pinned copy.
            val retained = assertNotNull(
                candidates.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(
                            LaunchSettings(runtime = RuntimePreference.Managed),
                            ManagedInstall(
                                "2.3.0-hb1",
                                fixture.executable.toString(),
                                Instant.fromEpochSeconds(0),
                                executableSha256 = fixture.digest,
                            ),
                        ),
                    ),
                    deadline,
                ) { error("must not promote") },
            )
            assertUnknownWithoutProbe(retained, deadline)
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `the candidate promotes the exact stamp once`() = runTest {
        temporary { fixture ->
            val probe = FakeBaselineProbe()
            val candidates = fixture.candidates(probe)
            val deadline = deadline()
            val policy = assertNotNull(KimiJvmCompatibility.requirements(HostPlatform.MacOs))
            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(fixture.request(recorded = fixture.digest), deadline) { stamp ->
                    promoted += stamp
                    FakeRuntime(
                        identity,
                        stamp,
                        "2.4.0-hb7",
                        EngineProtocolIdentity.Negotiated("acp", 1),
                        fixture.executable.toString(),
                    )
                },
            )
            val decision = assessEngineCompatibility(policy, candidate.evidence)
            val stamp = assertNotNull(decision.stamp)
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
            val probe = FakeBaselineProbe()
            val candidates = fixture.candidates(probe)
            val deadline = deadline()
            val policy = assertNotNull(KimiJvmCompatibility.requirements(HostPlatform.MacOs))
            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(fixture.request(recorded = fixture.digest), deadline) { stamp ->
                    promoted += stamp
                    FakeRuntime(
                        identity,
                        stamp,
                        "2.4.0-hb7",
                        EngineProtocolIdentity.Negotiated("acp", 1),
                        fixture.executable.toString(),
                    )
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
            val probe = FakeBaselineProbe()
            val candidates = fixture.candidates(probe)
            val deadline = deadline()
            val policy = assertNotNull(KimiJvmCompatibility.requirements(HostPlatform.MacOs))

            assertRejectedPromotion(fixture, candidates, policy, deadline) { stamp ->
                FakeRuntime(
                    identity,
                    stamp,
                    "0.0.0",
                    EngineProtocolIdentity.Negotiated("acp", 1),
                    fixture.executable.toString(),
                )
            }
            assertRejectedPromotion(fixture, candidates, policy, deadline) { stamp ->
                FakeRuntime(
                    identity,
                    stamp,
                    "2.4.0-hb7",
                    EngineProtocolIdentity.Negotiated("acp", 2),
                    fixture.executable.toString(),
                )
            }
            assertRejectedPromotion(fixture, candidates, policy, deadline) { stamp ->
                FakeRuntime(
                    identity,
                    stamp,
                    "2.4.0-hb7",
                    EngineProtocolIdentity.Negotiated("acp", 1),
                    "/different/kimi",
                )
            }

            var changed: FakeRuntime? = null
            val candidate = assertNotNull(
                candidates.open(fixture.request(recorded = fixture.digest), deadline) { stamp ->
                    FakeRuntime(
                        identity,
                        stamp,
                        "2.4.0-hb7",
                        EngineProtocolIdentity.Negotiated("acp", 1),
                        fixture.executable.toString(),
                    ).also { changed = it }
                },
            )
            val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
            Files.writeString(fixture.executable, "replaced binary")
            val failure = assertFailsWith<EngineException> { candidate.promote(stamp, deadline) }
            assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
            assertTrue(assertNotNull(changed).isClosed)
        }
    }

    private suspend fun assertRejectedPromotion(
        fixture: Fixture,
        candidates: JvmKimiCandidates,
        policy: EngineCompatibilityRequirements,
        deadline: TimeMark,
        runtime: (EngineCompatibilityStamp) -> FakeRuntime,
    ) {
        var created: FakeRuntime? = null
        val candidate = assertNotNull(
            candidates.open(fixture.request(recorded = fixture.digest), deadline) { stamp ->
                runtime(stamp).also { created = it }
            },
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
                    KimiEngine.Id,
                    "heartbeat",
                    "connector",
                    "2.4.0-hb7",
                    KimiJvmCompatibility.PROTOCOL,
                    0,
                    0,
                    "darwin-arm64",
                    "digest",
                ),
                deadline,
            )
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
        withContext(NonCancellable) { candidate.close() }
    }

    private fun baseline(
        initialize: KimiBaselineOutcome = KimiBaselineOutcome.Confirmed,
        permissionGate: KimiBaselineOutcome = KimiBaselineOutcome.Confirmed,
        hostedCallBinding: KimiBaselineOutcome = KimiBaselineOutcome.Confirmed,
    ) = KimiBaselineReceipt(initialize, permissionGate, hostedCallBinding, runtimeVersion = "2.4.0-hb7")

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
        private val root: Path = Files.createTempDirectory("kimi-admission-test-")
        val target: String = requireNotNull(kimiRuntimeTarget())
        private val executableName = if (System.getProperty("os.name").startsWith("Windows")) "kimi.exe" else "kimi"
        val executable: Path = root.resolve(executableName).apply {
            Files.writeString(this, "controlled protocol fixture")
            check(toFile().setExecutable(true))
        }
        val digest: String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)),
        )

        init {
            Files.writeString(
                root.resolve("runtime.properties"),
                "version=$KIMI_RUNTIME_VERSION\nsourceCommit=$KIMI_SOURCE_COMMIT\n" +
                    "permissionGate=1\nsha256=$digest\ntarget=$target\n",
            )
        }

        fun setup(): KimiRuntimeSetup = KimiRuntimeSetup(
            NoManagedKimiRuntime,
            TestKimiDispatchers,
            resourcesDirectory = null,
            isTrusted = { true },
        )

        fun candidates(
            probe: KimiBaselineProbe,
            isTrusted: (KimiControlledRuntime) -> Boolean = { true },
        ): JvmKimiCandidates = JvmKimiCandidates(
            setup(),
            object : PlatformInfo {
                override val host = HostPlatform.MacOs
            },
            TestKimiDispatchers,
            probe,
            isTrusted,
        )

        fun request(recorded: String? = null): EngineRuntimeCandidateRequest = EngineRuntimeCandidateRequest(
            RuntimeIdentity(KimiEngine.Id, AuthSourceId("synthetic"), AuthRevision.Known("1")),
            LaunchContext(
                LaunchSettings(runtime = RuntimePreference.Managed),
                ManagedInstall(
                    KIMI_RUNTIME_VERSION,
                    executable.toString(),
                    Instant.fromEpochSeconds(0),
                    executableSha256 = recorded,
                ),
            ),
        )

        override fun close() {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private class FakeBaselineProbe : KimiBaselineProbe {
        var calls = 0
        var lastInstallation: Installation? = null
        var receipt = KimiBaselineReceipt(
            KimiBaselineOutcome.Confirmed,
            KimiBaselineOutcome.Confirmed,
            KimiBaselineOutcome.Confirmed,
            runtimeVersion = "2.4.0-hb7",
        )

        override suspend fun run(installation: Installation, launch: LaunchContext): KimiBaselineReceipt {
            calls++
            lastInstallation = installation
            return receipt
        }
    }

    private class FakeRuntime(
        override val identity: RuntimeIdentity,
        override val compatibilityStamp: EngineCompatibilityStamp?,
        override val observedRuntimeVersion: String?,
        override val observedProtocol: EngineProtocolIdentity?,
        override val selectedExecutable: String?,
    ) : KimiAdmittedRuntime {
        override val features: EngineFeatures = object : EngineFeatures {
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                FeatureAccess.Unsupported
        }
        override var isClosed: Boolean = false
            private set

        override suspend fun close() {
            isClosed = true
        }
    }
}
