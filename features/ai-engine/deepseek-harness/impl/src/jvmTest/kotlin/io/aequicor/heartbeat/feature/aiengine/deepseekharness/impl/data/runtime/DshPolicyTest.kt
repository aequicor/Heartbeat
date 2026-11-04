package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekHost
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshExternalFingerprint
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.generated.DshBuildContract
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.assessEngineCompatibility
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshPolicyTest {
    @Test
    fun `the desktop policy is complete, unverified and admits the external fingerprint`() {
        val policy = assertNotNull(DshJvmCompatibility.requirements(HostPlatform.MacOs))
        assertEquals(DeepSeekEngine.Id, policy.engine)
        assertEquals(setOf(DshBuildContract.heartbeat), policy.heartbeatVersions)
        assertEquals(setOf(DshBuildContract.connector), policy.connectorVersions)
        assertEquals("0.2.1-rc.5", policy.minimumRuntime)
        assertNull(policy.maximumRuntimeExclusive, "the external line must not invent an upper bound")
        assertEquals(setOf(EngineProtocolIdentity.Negotiated("acp", 1)), policy.protocols)
        assertEquals(setOf(0), policy.configurationSchemas)
        assertEquals(setOf(0), policy.historySchemas)
        assertEquals(DshJvmCompatibility.nativeTargets, policy.nativeTargets)
        assertEquals(setOf("acp.initialize"), policy.baselineCapabilities)
        assertTrue(policy.verified.isEmpty(), "verified must stay empty")

        val fingerprint = DshExternalFingerprint.PREFIX + "a".repeat(64)
        val evidence = DshJvmCompatibility.evidence(
            DshArtifactFacts("0.2.1-rc.5", "darwin-arm64", fingerprint),
            baseline(),
            isProvenanceConfirmed = true,
        )
        assertEquals(fingerprint, evidence.identity.runtimeFingerprint)
        assertTrue(DshExternalFingerprint.isExternal(fingerprint), "the external policy is machine-visible")
        val decision = assessEngineCompatibility(policy, evidence)
        assertEquals(Compatibility.Unverified, decision.status, decision.problems.toString())
        assertTrue(decision.isBaselineAllowed)
        assertFalse(decision.isAutomaticUpdateAllowed)
        assertEquals(setOf("acp.initialize"), evidence.capabilities)
    }

    @Test
    fun `desktop targets are observed from the host and unknown architectures stay unestablished`() {
        assertEquals("darwin-arm64", dshNativeTarget(DeepSeekHost(osName = "Mac OS X", osArch = "aarch64")))
        assertEquals("win32-x64", dshNativeTarget(DeepSeekHost(osName = "Windows 11", osArch = "amd64")))
        assertEquals("linux-arm64", dshNativeTarget(DeepSeekHost(osName = "Linux", osArch = "arm64")))
        assertNull(dshNativeTarget(DeepSeekHost(osName = "Linux", osArch = "riscv64")))
        assertNull(dshNativeTarget(DeepSeekHost(osName = "Plan 9", osArch = "amd64")))
    }

    @Test
    fun `unobserved checks stay inconclusive and a conclusive failure rejects the baseline`() {
        val facts = DshArtifactFacts("0.2.1-rc.5", "darwin-arm64", DshExternalFingerprint.PREFIX + "a".repeat(64))
        val confirmed = DshJvmCompatibility.evidence(facts, baseline(), isProvenanceConfirmed = true)
        assertEquals(true, confirmed.isBaselineConfirmed)
        assertEquals(setOf("acp.initialize"), confirmed.capabilities)

        val unobserved = DshJvmCompatibility.evidence(
            facts,
            baseline(DshBaselineOutcome.Unobserved),
            isProvenanceConfirmed = true,
        )
        assertNull(unobserved.isBaselineConfirmed)
        assertNull(unobserved.capabilities)

        val rejected = DshJvmCompatibility.evidence(
            facts,
            baseline(DshBaselineOutcome.Rejected),
            isProvenanceConfirmed = true,
        )
        assertEquals(false, rejected.isBaselineConfirmed)
        assertNull(rejected.capabilities)

        val missingBaseline = DshJvmCompatibility.evidence(facts, baseline = null, isProvenanceConfirmed = true)
        assertNull(missingBaseline.isBaselineConfirmed)
        assertNull(missingBaseline.capabilities)

        val unknownFacts = DshArtifactFacts(runtime = null, nativeTarget = null, runtimeFingerprint = null)
        val unknown = DshJvmCompatibility.evidence(unknownFacts, baseline = null, isProvenanceConfirmed = null)
        assertNull(unknown.isBaselineConfirmed)
        assertNull(unknown.isProvenanceConfirmed)
        assertNull(unknown.capabilities)
        assertNull(unknown.identity.runtimeFingerprint)
    }

    @Test
    fun `a raw digest is never promoted to the external provenance marker`() {
        val evidence = DshJvmCompatibility.evidence(
            DshArtifactFacts("0.2.1-rc.5", "darwin-arm64", "a".repeat(64)),
            baseline(),
            isProvenanceConfirmed = true,
        )
        assertNull(evidence.isProvenanceConfirmed)
    }

    @Test
    fun `the external fingerprint marks the selected executable and refuses missing files`() {
        val root = Files.createTempDirectory("dsh-fingerprint-test-")
        try {
            val executable = Files.writeString(root.resolve("dsh"), "controlled external fixture")
            val expected = DshExternalFingerprint.PREFIX + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)),
            )
            assertEquals(expected, DshExternalFingerprint.of(executable.toString()))
            Files.writeString(executable, "replaced external fixture")
            assertTrue(DshExternalFingerprint.of(executable.toString()) != expected)
            assertNull(DshExternalFingerprint.of(root.resolve("missing").toString()))
            assertNull(DshExternalFingerprint.of(root.toString()))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun baseline(initialize: DshBaselineOutcome = DshBaselineOutcome.Confirmed) =
        DshBaselineReceipt(initialize, runtimeVersion = "0.2.1-rc.5")
}
