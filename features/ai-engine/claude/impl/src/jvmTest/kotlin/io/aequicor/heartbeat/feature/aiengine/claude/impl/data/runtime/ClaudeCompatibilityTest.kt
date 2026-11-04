package io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.CLAUDE_NATIVE_GATE_FLOOR
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeFixture
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeHost
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.ClaudeRuntimeAdmission
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.claudeArguments
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.generated.ClaudeBuildContract
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.supportsClaudeNativeGate
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeDesktopSwitch
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
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

class ClaudeCompatibilityTest {

    /** Promotion must never be reached by these cases; the request exists only to build a candidate. */
    private suspend fun refusePromotion(executable: String?, stamp: EngineCompatibilityStamp): EngineRuntime =
        error("promotion is not expected for $executable at $stamp")
    private val identity = RuntimeIdentity(ClaudeEngine.Id, AuthSourceId("synthetic"), AuthRevision.Unknown)

    @Test
    fun `the desktop policy is complete, unverified and admits the assessed baseline`() {
        val policy = assertNotNull(ClaudeJvmCompatibility.requirements(HostPlatform.MacOs, "darwin-arm64"))
        assertEquals(ClaudeEngine.Id, policy.engine)
        assertEquals(setOf(ClaudeBuildContract.heartbeat), policy.heartbeatVersions)
        assertEquals(setOf(ClaudeBuildContract.connector), policy.connectorVersions)
        assertEquals(CLAUDE_NATIVE_GATE_FLOOR, policy.minimumRuntime)
        assertEquals("2.1.285", policy.minimumRuntime)
        assertEquals(setOf(EngineProtocolIdentity.ProbedSchema("claude-cli-stream", "1")), policy.protocols)
        assertEquals(setOf(ClaudeConfigurationFormat.CURRENT_REVISION), policy.configurationSchemas)
        assertEquals(setOf(ClaudeHistoryFormat.CURRENT_REVISION), policy.historySchemas)
        assertEquals(setOf("darwin-arm64"), policy.nativeTargets)
        assertEquals(ClaudeJvmCompatibility.baselineCapabilities, policy.baselineCapabilities)
        assertTrue(policy.verified.isEmpty(), "verified must stay empty")
        // The declared floor is the adapter's own native-gate boundary, not a separately invented range.
        assertTrue(supportsClaudeNativeGate(CLAUDE_NATIVE_GATE_FLOOR))
        assertFalse(supportsClaudeNativeGate("2.1.284"))

        val evidence = ClaudeJvmCompatibility.evidence(
            ClaudeArtifactFacts("2.1.285", "darwin-arm64", "a".repeat(64)),
            ClaudeBaselineReceipt(ClaudeBaselineOutcome.Confirmed, ClaudeBaselineOutcome.Confirmed),
            isProvenanceConfirmed = true,
        )
        val decision = assessEngineCompatibility(policy, evidence)
        assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
        assertTrue(decision.isBaselineAllowed)
        assertFalse(decision.isAutomaticUpdateAllowed)
        assertEquals(setOf("stream.initialize", "catalog.models"), evidence.capabilities)
    }

    @Test
    fun `mobile and unknown targets have no policy at all`() = runTest {
        temporary { root ->
            val probe = FakeBaselineProbe()
            assertNull(JvmClaudeCompatibility(platform(HostPlatform.Android), host(root)).requirements())
            assertNull(JvmClaudeCompatibility(platform(HostPlatform.Ios), host(root)).requirements())
            assertNull(ClaudeJvmCompatibility.requirements(HostPlatform.MacOs, null))
            assertNull(ClaudeJvmCompatibility.requirements(HostPlatform.Android, "linux-arm64"))
            assertNull(UnsupportedClaudeCompatibility.requirements())

            val mobile = JvmClaudeCandidates(
                ClaudeConfiguration(),
                platform(HostPlatform.Android),
                host(root),
                ClaudeDesktopSwitch.On,
                dispatchers(),
                probe,
            )
            val request = EngineRuntimeCandidateRequest(identity, LaunchContext())
            assertNull(mobile.open(request, TimeSource.Monotonic.markNow() + 10.seconds, ::refusePromotion))
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `unobserved checks stay inconclusive and a conclusive failure rejects the baseline`() {
        val facts = ClaudeArtifactFacts("2.1.285", "darwin-arm64", "a".repeat(64))
        val confirmed = ClaudeJvmCompatibility.evidence(
            facts,
            ClaudeBaselineReceipt(ClaudeBaselineOutcome.Confirmed, ClaudeBaselineOutcome.Confirmed),
            isProvenanceConfirmed = true,
        )
        assertEquals(true, confirmed.isBaselineConfirmed)
        assertEquals(ClaudeJvmCompatibility.baselineCapabilities, confirmed.capabilities)

        val unobserved = ClaudeJvmCompatibility.evidence(
            facts,
            ClaudeBaselineReceipt(ClaudeBaselineOutcome.Confirmed, ClaudeBaselineOutcome.Unobserved),
            isProvenanceConfirmed = true,
        )
        assertNull(unobserved.isBaselineConfirmed)
        assertNull(unobserved.capabilities)

        val rejected = ClaudeJvmCompatibility.evidence(
            facts,
            ClaudeBaselineReceipt(ClaudeBaselineOutcome.Confirmed, ClaudeBaselineOutcome.Rejected),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, rejected.isBaselineConfirmed)
        assertNull(rejected.capabilities)

        val failedHandshake = ClaudeJvmCompatibility.evidence(
            facts,
            ClaudeBaselineReceipt(ClaudeBaselineOutcome.Rejected, ClaudeBaselineOutcome.Unobserved),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, failedHandshake.isBaselineConfirmed)
        assertNull(failedHandshake.capabilities)

        val unknownFacts = ClaudeArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null)
        val unknown = ClaudeJvmCompatibility.evidence(unknownFacts, baseline = null, isProvenanceConfirmed = null)
        assertNull(unknown.isBaselineConfirmed)
        assertNull(unknown.isProvenanceConfirmed)
        assertNull(unknown.capabilities)
        assertNull(unknown.identity.runtimeFingerprint)
    }

    @Test
    fun `a managed copy is observed only while its executable digest matches the store record`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds

            val verified = assertNotNull(candidates.open(request(executable, digest), deadline, ::refusePromotion))
            try {
                assertEquals(true, verified.evidence.isProvenanceConfirmed)
                assertEquals(true, verified.evidence.isBaselineConfirmed)
                assertEquals("2.1.285", verified.evidence.identity.runtime)
                assertEquals("darwin-arm64", verified.evidence.identity.nativeTarget)
                assertEquals(digest, verified.evidence.identity.runtimeFingerprint)
                assertEquals(1, probe.calls)
                assertEquals(executable, probe.lastExecutable)
            } finally {
                withContext(NonCancellable) { verified.close() }
            }

            val mismatched = assertNotNull(
                candidates.open(request(executable, "0".repeat(64)), deadline, ::refusePromotion),
            )
            try {
                assertEquals(false, mismatched.evidence.isProvenanceConfirmed)
                assertNull(mismatched.evidence.isBaselineConfirmed)
                assertNull(mismatched.evidence.identity.runtime)
                assertNull(mismatched.evidence.identity.runtimeFingerprint)
                assertEquals(1, probe.calls)
            } finally {
                withContext(NonCancellable) { mismatched.close() }
            }

            val unrecorded = assertNotNull(
                candidates.open(request(executable, digest = null), deadline, ::refusePromotion),
            )
            try {
                assertNull(unrecorded.evidence.isProvenanceConfirmed)
                assertNull(unrecorded.evidence.isBaselineConfirmed)
                assertNull(unrecorded.evidence.identity.runtimeFingerprint)
                assertEquals(1, probe.calls)
            } finally {
                withContext(NonCancellable) { unrecorded.close() }
            }

            // The release archive digest, even when it equals the file's digest, is never executable provenance.
            val archiveOnly = assertNotNull(
                candidates.open(
                    request(executable, digest = null, archiveDigest = digest),
                    deadline,
                    ::refusePromotion,
                ),
            )
            try {
                assertNull(archiveOnly.evidence.isProvenanceConfirmed)
                assertNull(archiveOnly.evidence.isBaselineConfirmed)
                assertNull(archiveOnly.evidence.identity.runtimeFingerprint)
                assertEquals(1, probe.calls)
            } finally {
                withContext(NonCancellable) { archiveOnly.close() }
            }
        }
    }

    @Test
    fun `the managed candidate promotes the exact stamp once`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val policy = assertNotNull(
                ClaudeJvmCompatibility.requirements(HostPlatform.MacOs, "darwin-arm64"),
            )
            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(request(executable, digest), deadline) { executable, stamp ->
                    promoted += stamp
                    FakeRuntime(identity, stamp, "2.1.285", executable.toString())
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
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val policy = assertNotNull(
                ClaudeJvmCompatibility.requirements(HostPlatform.MacOs, "darwin-arm64"),
            )
            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(request(executable, digest), deadline) { executable, stamp ->
                    promoted += stamp
                    FakeRuntime(identity, stamp, "2.1.285", executable.toString())
                },
            )
            try {
                val stamp = assertNotNull(assessEngineCompatibility(policy, candidate.evidence).stamp)
                val forged = stamp.copy(runtime = "0.0.0")
                val failure = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
                assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure.failure)
                assertTrue(promoted.isEmpty(), "a foreign stamp must not transfer a runtime")
            } finally {
                withContext(NonCancellable) { candidate.close() }
            }
        }
    }

    @Test
    fun `a promoted runtime with a changed version or digest fails closed`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val policy = assertNotNull(
                ClaudeJvmCompatibility.requirements(HostPlatform.MacOs, "darwin-arm64"),
            )

            var changedVersion: FakeRuntime? = null
            val versionCandidate = assertNotNull(
                candidates.open(request(executable, digest), deadline) { executable, stamp ->
                    FakeRuntime(identity, stamp, "2.1.286", executable.toString()).also { changedVersion = it }
                },
            )
            val versionStamp = assertNotNull(assessEngineCompatibility(policy, versionCandidate.evidence).stamp)
            val versionFailure = assertFailsWith<EngineException> {
                versionCandidate.promote(versionStamp, deadline)
            }
            assertEquals(
                EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown),
                versionFailure.failure,
            )
            assertTrue(assertNotNull(changedVersion).isClosed)

            var changedDigest: FakeRuntime? = null
            val digestCandidate = assertNotNull(
                candidates.open(request(executable, digest), deadline) { executable, stamp ->
                    FakeRuntime(identity, stamp, "2.1.285", executable.toString()).also { changedDigest = it }
                },
            )
            val digestStamp = assertNotNull(assessEngineCompatibility(policy, digestCandidate.evidence).stamp)
            Files.writeString(executable, "replaced binary")
            val digestFailure = assertFailsWith<EngineException> {
                digestCandidate.promote(digestStamp, deadline)
            }
            assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), digestFailure.failure)
            assertTrue(assertNotNull(changedDigest).isClosed)
        }
    }

    @Test
    fun `a custom, system or host-app executable has no pinned identity and transfers nothing`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds

            val custom = candidates(root, probe)
            val customCandidate = assertNotNull(
                custom.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(LaunchSettings(executable = executable.toString())),
                    ),
                    deadline,
                    ::refusePromotion,
                ),
            )
            assertUnknownWithoutProbe(customCandidate, probe, deadline)

            val system = JvmClaudeCandidates(
                ClaudeConfiguration(executable = executable.toString()),
                platform(HostPlatform.MacOs),
                host(root),
                ClaudeDesktopSwitch.On,
                dispatchers(),
                probe,
            )
            val systemCandidate = assertNotNull(
                system.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(LaunchSettings(runtime = RuntimePreference.System)),
                    ),
                    deadline,
                    ::refusePromotion,
                ),
            )
            assertUnknownWithoutProbe(systemCandidate, probe, deadline)

            // The Claude desktop app's runtime is verified by the app but has no recorded executable digest.
            hostAppExecutable(root)
            val hostApp = candidates(root, probe)
            val hostAppCandidate = assertNotNull(
                hostApp.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(
                            LaunchSettings(runtime = RuntimePreference.HostApp),
                            ManagedInstall(
                                "2.1.285",
                                executable.toString(),
                                Instant.fromEpochSeconds(0),
                                executableSha256 = digest,
                            ),
                        ),
                    ),
                    deadline,
                    ::refusePromotion,
                ),
            )
            assertUnknownWithoutProbe(hostAppCandidate, probe, deadline)
        }
    }

    @Test
    fun `the ordinary runtime exposes the admitted stamp and observed facts`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val stamp = EngineCompatibilityStamp(
            ClaudeEngine.Id,
            ClaudeBuildContract.heartbeat,
            ClaudeBuildContract.connector,
            "2.1.285",
            ClaudeJvmCompatibility.protocol,
            0,
            0,
            "darwin-arm64",
            "a".repeat(64),
        )
        val runtime = fixture.runtime(
            admission = ClaudeRuntimeAdmission(stamp, observedVersion = "2.1.285", selectedExecutable = "/data/claude"),
        )
        assertEquals(stamp, runtime.compatibilityStamp)
        assertEquals("2.1.285", runtime.observedVersion)
        assertEquals("/data/claude", runtime.selectedExecutable)
        runtime.close()
    }

    @Test
    fun `the baseline command is the existing stream-json initialize handshake`() {
        val command = claudeBaselineArguments(Path.of("/data/claude"))
        assertEquals(
            listOf(Path.of("/data/claude").toString()) + claudeArguments() + listOf("--input-format", "stream-json"),
            command,
        )
        assertTrue("--print" in command)
        assertTrue("--setting-sources=" in command)
        assertTrue("--strict-mcp-config" in command)
    }

    @Test
    fun `an expired deadline observes nothing and starts no process`() = runTest {
        val receipt = JvmClaudeBaselineProbe(dispatchers()).run(
            Path.of("/nonexistent/claude"),
            TimeSource.Monotonic.markNow(),
        )
        assertEquals(ClaudeBaselineOutcome.Unobserved, receipt.initialize)
        assertEquals(ClaudeBaselineOutcome.Unobserved, receipt.models)
        assertFalse(receipt.hasRejectedCheck)
    }

    private suspend fun assertUnknownWithoutProbe(
        candidate: EngineRuntimeCandidate,
        probe: FakeBaselineProbe,
        deadline: TimeMark,
    ) {
        assertNull(candidate.evidence.isProvenanceConfirmed)
        assertNull(candidate.evidence.isBaselineConfirmed)
        assertNull(candidate.evidence.identity.runtime)
        assertNull(candidate.evidence.identity.nativeTarget)
        assertNull(candidate.evidence.identity.runtimeFingerprint)
        assertEquals(0, probe.calls)
        val forged = EngineCompatibilityStamp(
            ClaudeEngine.Id,
            "heartbeat",
            "connector",
            "2.1.285",
            ClaudeJvmCompatibility.protocol,
            0,
            0,
            "darwin-arm64",
            "digest",
        )
        val failure = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
        withContext(NonCancellable) { candidate.close() }
    }

    private fun TestScope.candidates(root: Path, probe: FakeBaselineProbe): JvmClaudeCandidates = JvmClaudeCandidates(
        ClaudeConfiguration(executable = "claude"),
        platform(HostPlatform.MacOs),
        host(root),
        ClaudeDesktopSwitch.On,
        dispatchers(),
        probe,
    )

    private fun request(
        executable: Path,
        digest: String?,
        archiveDigest: String? = null,
    ): EngineRuntimeCandidateRequest = EngineRuntimeCandidateRequest(
        identity,
        LaunchContext(
            LaunchSettings(runtime = RuntimePreference.Managed),
            ManagedInstall(
                "2.1.285",
                executable.toString(),
                Instant.fromEpochSeconds(0),
                sha256 = archiveDigest,
                executableSha256 = digest,
            ),
        ),
    )

    private fun host(root: Path): ClaudeHost = ClaudeHost(
        osName = "Mac OS X",
        osArch = "aarch64",
        path = "",
        userHome = root.toString(),
        entries = { it.listFiles()?.toList().orEmpty() },
        isExecutable = { it.isFile },
        environment = emptyMap(),
        exists = File::exists,
    )

    private fun platform(host: HostPlatform): PlatformInfo = object : PlatformInfo {
        override val host = host
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.dispatchers(): DispatcherProvider {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
    }

    private fun executable(root: Path): Path {
        val executable = Files.writeString(root.resolve("claude"), "binary")
        assertTrue(executable.toFile().setExecutable(true))
        return executable
    }

    private fun hostAppExecutable(root: Path): Path {
        val build = root.resolve("Library/Application Support/Claude/claude-code/2.1.285/b1")
        val executable = build.resolve("claude.app/Contents/MacOS/claude")
        Files.createDirectories(executable.parent)
        Files.writeString(build.resolve(".verified"), "")
        Files.writeString(executable, "binary")
        assertTrue(executable.toFile().setExecutable(true))
        return executable
    }

    private fun digest(path: Path): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),
    )

    private suspend fun <T> temporary(block: suspend (Path) -> T): T {
        val directory = Files.createTempDirectory("claude-runtime-test-")
        try {
            return block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class FakeBaselineProbe : ClaudeBaselineProbe {
        var calls = 0
        var lastExecutable: Path? = null
        var receipt = ClaudeBaselineReceipt(
            ClaudeBaselineOutcome.Confirmed,
            ClaudeBaselineOutcome.Confirmed,
        )

        override suspend fun run(executable: Path, deadline: TimeMark): ClaudeBaselineReceipt {
            calls++
            lastExecutable = executable
            return receipt
        }
    }

    private class FakeRuntime(
        override val identity: RuntimeIdentity,
        override val compatibilityStamp: EngineCompatibilityStamp?,
        override val observedVersion: String?,
        override val selectedExecutable: String?,
    ) : ClaudeAdmittedRuntime {
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
