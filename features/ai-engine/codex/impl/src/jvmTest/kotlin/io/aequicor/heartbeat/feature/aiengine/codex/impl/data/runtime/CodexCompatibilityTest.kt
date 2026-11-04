package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CODEX_APP_SERVER_FLOOR
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexAdmittedRuntime
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexHost
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexTarget
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.codexReleaseTarget
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.generated.CodexBuildContract
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

class CodexCompatibilityTest {
    private val identity = RuntimeIdentity(CodexEngine.Id, AuthSourceId("synthetic"), AuthRevision.Unknown)

    @Test
    fun `the desktop policy is complete, unverified and admits the assessed baseline`() {
        val target = CodexTarget("aarch64-apple-darwin", "bin/codex")
        val policy = assertNotNull(CodexJvmCompatibility.requirements(HostPlatform.MacOs, target))
        assertEquals(CodexEngine.Id, policy.engine)
        assertEquals(setOf(CodexBuildContract.heartbeat), policy.heartbeatVersions)
        assertEquals(setOf(CodexBuildContract.connector), policy.connectorVersions)
        assertEquals(CODEX_APP_SERVER_FLOOR, policy.minimumRuntime)
        assertEquals(setOf(EngineProtocolIdentity.ProbedSchema("codex-app-server", "1")), policy.protocols)
        assertEquals(setOf(0), policy.configurationSchemas)
        assertEquals(setOf(0), policy.historySchemas)
        assertEquals(setOf(target.triple), policy.nativeTargets)
        assertEquals(CodexJvmCompatibility.baselineCapabilities, policy.baselineCapabilities)
        assertTrue(policy.verified.isEmpty(), "verified must stay empty")

        val evidence = CodexJvmCompatibility.evidence(
            CodexArtifactFacts("0.170.0", target.triple, "a".repeat(64)),
            CodexBaselineReceipt(CodexBaselineOutcome.Confirmed, CodexBaselineOutcome.Confirmed, "0.170.0"),
            isProvenanceConfirmed = true,
        )
        val decision = assessEngineCompatibility(policy, evidence)
        assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
        assertTrue(decision.isBaselineAllowed)
        assertFalse(decision.isAutomaticUpdateAllowed)
        assertEquals(setOf("app-server.initialize", "config.read"), evidence.capabilities)
    }

    @Test
    fun `desktop release targets stay exact and mobile or unknown targets have no policy`() {
        val mac = assertNotNull(
            codexReleaseTarget(CodexHost(osName = "Mac OS X", osArch = "aarch64")),
        )
        val windows = assertNotNull(
            codexReleaseTarget(CodexHost(osName = "Windows 11", osArch = "x86_64")),
        )
        val linux = assertNotNull(
            codexReleaseTarget(CodexHost(osName = "Linux", osArch = "amd64")),
        )
        assertEquals("aarch64-apple-darwin", mac.triple)
        assertEquals("x86_64-pc-windows-msvc", windows.triple)
        assertEquals("x86_64-unknown-linux-musl", linux.triple)
        assertEquals(
            setOf(windows.triple),
            CodexJvmCompatibility.requirements(HostPlatform.Windows, windows)?.nativeTargets,
        )
        assertEquals(
            setOf(linux.triple),
            CodexJvmCompatibility.requirements(HostPlatform.Linux, linux)?.nativeTargets,
        )
        assertNull(CodexJvmCompatibility.requirements(HostPlatform.MacOs, null))
        assertNull(JvmCodexCompatibility(platform(HostPlatform.Android)).requirements())
        assertNull(JvmCodexCompatibility(platform(HostPlatform.Ios)).requirements())
        assertNull(UnsupportedCodexCompatibility.requirements())
    }

    @Test
    fun `unobserved checks stay inconclusive and a conclusive failure rejects the baseline`() {
        val target = CodexTarget("aarch64-apple-darwin", "bin/codex")
        val facts = CodexArtifactFacts("0.170.0", target.triple, "a".repeat(64))
        val confirmed = CodexJvmCompatibility.evidence(
            facts,
            CodexBaselineReceipt(CodexBaselineOutcome.Confirmed, CodexBaselineOutcome.Confirmed, "0.170.0"),
            isProvenanceConfirmed = true,
        )
        assertEquals(true, confirmed.isBaselineConfirmed)
        assertEquals(CodexJvmCompatibility.baselineCapabilities, confirmed.capabilities)

        val unobserved = CodexJvmCompatibility.evidence(
            facts,
            CodexBaselineReceipt(CodexBaselineOutcome.Confirmed, CodexBaselineOutcome.Unobserved, "0.170.0"),
            isProvenanceConfirmed = true,
        )
        assertNull(unobserved.isBaselineConfirmed)
        assertNull(unobserved.capabilities)

        val rejected = CodexJvmCompatibility.evidence(
            facts,
            CodexBaselineReceipt(CodexBaselineOutcome.Confirmed, CodexBaselineOutcome.Rejected, "0.170.0"),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, rejected.isBaselineConfirmed)
        assertNull(rejected.capabilities)

        val missingHandshake = CodexJvmCompatibility.evidence(
            facts,
            CodexBaselineReceipt(CodexBaselineOutcome.Unobserved, CodexBaselineOutcome.Unobserved),
            isProvenanceConfirmed = null,
        )
        assertNull(missingHandshake.isBaselineConfirmed)
        assertNull(missingHandshake.isProvenanceConfirmed)
        assertNull(missingHandshake.capabilities)
    }

    @Test
    fun `a managed copy is observed only while its executable digest matches the store record`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds

            val verified = assertNotNull(
                candidates.open(request(executable, digest, RuntimePreference.Managed), deadline) { error("unused") },
            )
            try {
                assertEquals(true, verified.evidence.isProvenanceConfirmed)
                assertEquals(true, verified.evidence.isBaselineConfirmed)
                assertEquals("0.170.0", verified.evidence.identity.runtime)
                assertEquals("aarch64-apple-darwin", verified.evidence.identity.nativeTarget)
                assertEquals(digest, verified.evidence.identity.runtimeFingerprint)
                assertEquals(1, probe.calls)
                assertEquals(executable, probe.lastExecutable)
            } finally {
                withContext(NonCancellable) { verified.close() }
            }

            val mismatched = assertNotNull(
                candidates.open(request(executable, "0".repeat(64), RuntimePreference.Managed), deadline) {
                    error("unused")
                },
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
                candidates.open(request(executable, null, RuntimePreference.Managed), deadline) { error("unused") },
            )
            try {
                assertNull(unrecorded.evidence.isProvenanceConfirmed)
                assertNull(unrecorded.evidence.isBaselineConfirmed)
                assertNull(unrecorded.evidence.identity.runtimeFingerprint)
                assertEquals(1, probe.calls)
            } finally {
                withContext(NonCancellable) { unrecorded.close() }
            }
        }
    }

    @Test
    fun `the release archive digest is not executable provenance`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds

            // The release archive digest, even when it equals the file's digest, is never executable provenance.
            val archiveOnly = assertNotNull(
                candidates.open(
                    EngineRuntimeCandidateRequest(
                        identity,
                        LaunchContext(
                            LaunchSettings(runtime = RuntimePreference.Managed),
                            ManagedInstall(
                                "0.170.0",
                                executable.toString(),
                                Instant.fromEpochSeconds(0),
                                sha256 = digest,
                            ),
                        ),
                    ),
                    deadline,
                ) { error("unused") },
            )
            try {
                assertNull(archiveOnly.evidence.isProvenanceConfirmed)
                assertNull(archiveOnly.evidence.isBaselineConfirmed)
                assertNull(archiveOnly.evidence.identity.runtimeFingerprint)
                assertEquals(0, probe.calls)
            } finally {
                withContext(NonCancellable) { archiveOnly.close() }
            }
        }
    }

    @Test
    fun `a custom or system executable has no pinned identity and transfers nothing`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val probe = FakeBaselineProbe()
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val custom = candidates(root, probe)
            val customCandidate = assertNotNull(
                custom.open(customRequest(executable), deadline) { error("must not promote") },
            )
            assertUnknownWithoutProbe(customCandidate, probe)
            assertPromotionRefused(customCandidate, deadline)

            val systemConfig = CodexLocalConfiguration(executable = executable.toString())
            val system = JvmCodexCandidates(
                systemConfig,
                platform(HostPlatform.MacOs),
                host(root),
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
                ) { error("must not promote") },
            )
            assertUnknownWithoutProbe(systemCandidate, probe)
            assertPromotionRefused(systemCandidate, deadline)
        }
    }

    @Test
    fun `the managed candidate promotes the exact stamp once and refuses a foreign stamp`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val policy = assertNotNull(
                CodexJvmCompatibility.requirements(
                    HostPlatform.MacOs,
                    codexReleaseTarget(host(root)),
                ),
            )

            val foreign = assertNotNull(
                candidates.open(request(executable, digest, RuntimePreference.Managed), deadline) {
                    error("must not transfer")
                },
            )
            try {
                val decision = assessEngineCompatibility(policy, foreign.evidence)
                val stamp = assertNotNull(decision.stamp)
                val forged = stamp.copy(runtime = "0.0.0")
                val failure = assertFailsWith<EngineException> { foreign.promote(forged, deadline) }
                assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure.failure)
            } finally {
                withContext(NonCancellable) { foreign.close() }
            }

            val promoted = mutableListOf<EngineCompatibilityStamp>()
            val candidate = assertNotNull(
                candidates.open(request(executable, digest, RuntimePreference.Managed), deadline) { stamp ->
                    promoted += stamp
                    FakeRuntime(identity, stamp, "0.170.0", executable.toString())
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
    fun `a promoted runtime with a changed handshake version or executable digest fails closed`() = runTest {
        temporary { root ->
            val executable = executable(root)
            val digest = digest(executable)
            val probe = FakeBaselineProbe()
            val candidates = candidates(root, probe)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val policy = assertNotNull(
                CodexJvmCompatibility.requirements(
                    HostPlatform.MacOs,
                    codexReleaseTarget(host(root)),
                ),
            )

            var changedVersion: FakeRuntime? = null
            val versionCandidate = assertNotNull(
                candidates.open(request(executable, digest, RuntimePreference.Managed), deadline) { stamp ->
                    FakeRuntime(identity, stamp, "0.171.0", executable.toString()).also { changedVersion = it }
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
                candidates.open(request(executable, digest, RuntimePreference.Managed), deadline) { stamp ->
                    FakeRuntime(identity, stamp, "0.170.0", executable.toString()).also { changedDigest = it }
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
    fun `the baseline command is the existing clean-home app-server probe`() {
        assertEquals(
            listOf("/data/codex", "app-server"),
            codexBaselineArguments(Path.of("/data/codex")),
        )
    }

    @Test
    fun `an expired deadline observes nothing and starts no process`() = runTest {
        val receipt = JvmCodexBaselineProbe(dispatchers()).run(
            Path.of("/nonexistent/codex"),
            TimeSource.Monotonic.markNow(),
        )
        assertEquals(CodexBaselineOutcome.Unobserved, receipt.initialize)
        assertEquals(CodexBaselineOutcome.Unobserved, receipt.configRead)
        assertNull(receipt.version)
        assertFalse(receipt.hasRejectedCheck)
    }

    private fun assertUnknownWithoutProbe(candidate: EngineRuntimeCandidate, probe: FakeBaselineProbe) {
        assertNull(candidate.evidence.isProvenanceConfirmed)
        assertNull(candidate.evidence.isBaselineConfirmed)
        assertNull(candidate.evidence.identity.runtime)
        assertNull(candidate.evidence.identity.nativeTarget)
        assertNull(candidate.evidence.identity.runtimeFingerprint)
        assertEquals(0, probe.calls)
    }

    private suspend fun assertPromotionRefused(candidate: EngineRuntimeCandidate, deadline: TimeMark) {
        val forged = EngineCompatibilityStamp(
            CodexEngine.Id,
            "heartbeat",
            "connector",
            "0.170.0",
            CodexJvmCompatibility.protocol,
            0,
            0,
            "aarch64-apple-darwin",
            "digest",
        )
        val failure = assertFailsWith<EngineException> { candidate.promote(forged, deadline) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown), failure.failure)
    }

    private fun TestScope.candidates(root: Path, probe: FakeBaselineProbe): JvmCodexCandidates = JvmCodexCandidates(
        CodexLocalConfiguration(executable = "codex"),
        platform(HostPlatform.MacOs),
        host(root),
        dispatchers(),
        probe,
    )

    private fun request(executable: Path, digest: String?, runtime: RuntimePreference): EngineRuntimeCandidateRequest =
        EngineRuntimeCandidateRequest(
            identity,
            LaunchContext(
                LaunchSettings(runtime = runtime),
                ManagedInstall(
                    "0.170.0",
                    executable.toString(),
                    Instant.fromEpochSeconds(0),
                    executableSha256 = digest,
                ),
            ),
        )

    private fun customRequest(executable: Path): EngineRuntimeCandidateRequest = EngineRuntimeCandidateRequest(
        identity,
        LaunchContext(LaunchSettings(executable = executable.toString())),
    )

    private fun host(root: Path): CodexHost = CodexHost(
        osName = "Mac OS X",
        osArch = "aarch64",
        path = "",
        userHome = root.toString(),
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
        val executable = Files.writeString(root.resolve("codex"), "binary")
        assertTrue(executable.toFile().setExecutable(true))
        return executable
    }

    private fun digest(path: Path): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),
    )

    private suspend fun <T> temporary(block: suspend (Path) -> T): T {
        val directory = Files.createTempDirectory("codex-runtime-test-")
        try {
            return block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private class FakeBaselineProbe : CodexBaselineProbe {
        var calls = 0
        var lastExecutable: Path? = null
        var receipt = CodexBaselineReceipt(
            CodexBaselineOutcome.Confirmed,
            CodexBaselineOutcome.Confirmed,
            "0.170.0",
        )

        override suspend fun run(executable: Path, deadline: TimeMark): CodexBaselineReceipt {
            calls++
            lastExecutable = executable
            return receipt
        }
    }

    private class FakeRuntime(
        override val identity: RuntimeIdentity,
        override val compatibilityStamp: EngineCompatibilityStamp?,
        override val handshakeVersion: String?,
        override val selectedExecutable: String?,
    ) : CodexAdmittedRuntime {
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
