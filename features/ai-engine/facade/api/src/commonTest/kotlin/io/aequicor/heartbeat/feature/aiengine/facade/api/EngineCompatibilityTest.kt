package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineCompatibilityTest {
    private val stamp = EngineCompatibilityStamp(
        engine = EngineId("test"),
        heartbeat = "hb1",
        connector = "connector1",
        runtime = "1.2.0",
        protocol = 1,
        configurationSchema = 1,
        historySchema = 1,
        nativeTarget = "darwin-arm64",
        runtimeFingerprint = "sha256-a",
    )
    private val requirements = EngineCompatibilityRequirements(
        engine = EngineId("test"),
        heartbeatVersions = setOf("hb1", "hb2"),
        connectorVersions = setOf("connector1", "connector2"),
        minimumRuntime = "1.0.0",
        protocols = setOf(1),
        configurationSchemas = setOf(1),
        historySchemas = setOf(1),
        nativeTargets = setOf("darwin-arm64"),
        baselineCapabilities = setOf("cancel", "permission"),
        verified = setOf(stamp),
    )
    private val evidence = EngineCompatibilityEvidence(stamp.identity(), true, true, setOf("cancel", "permission"))

    @Test
    fun `only the exact observed regression bundle is verified`() {
        val result = assessEngineCompatibility(requirements, evidence)
        assertEquals(Compatibility.Verified, result.status)
        assertTrue(result.isBaselineAllowed)
        assertTrue(result.isAutomaticUpdateAllowed)
        listOf(
            stamp.copy(heartbeat = "hb2"),
            stamp.copy(connector = "connector2"),
            stamp.copy(runtime = "1.2.0+another-build"),
            stamp.copy(runtimeFingerprint = "sha256-b"),
        ).forEach {
            val changed = assessEngineCompatibility(requirements, evidence.copy(identity = it.identity()))
            assertEquals(Compatibility.Unverified, changed.status)
            assertTrue(changed.isBaselineAllowed)
            assertFalse(changed.isAutomaticUpdateAllowed)
        }
    }

    @Test
    fun `newer versions pass the safe baseline without an invented upper bound`() {
        val result = assessEngineCompatibility(
            requirements,
            evidence.copy(identity = evidence.identity.copy(runtime = "100.0.0")),
        )
        assertEquals(Compatibility.Unverified, result.status)
        assertTrue(result.isBaselineAllowed)
    }

    @Test
    fun `unknown identity and incomplete probes never admit a baseline`() {
        listOf(
            EngineCompatibilityEvidence(),
            evidence.copy(identity = EngineCompatibilityIdentity()),
            evidence.copy(identity = evidence.identity.copy(runtimeFingerprint = "")),
            evidence.copy(identity = evidence.identity.copy(heartbeat = "")),
            evidence.copy(identity = evidence.identity.copy(connector = "")),
            evidence.copy(isBaselineConfirmed = null),
            evidence.copy(isProvenanceConfirmed = null),
            evidence.copy(capabilities = null),
        ).forEach {
            val result = assessEngineCompatibility(requirements, it)
            assertEquals(Compatibility.Unknown, result.status)
            assertFalse(result.isBaselineAllowed)
            assertFalse(result.isAutomaticUpdateAllowed)
        }
    }

    @Test
    fun `malformed versions remain unknown instead of claiming incompatibility`() {
        listOf("", "v1.2.0", "1.2", "1.02.0", "1.2.0-01", "1.2.0+", "1.2.0\n", "9".repeat(257)).forEach {
            val result = assessEngineCompatibility(
                requirements,
                evidence.copy(identity = evidence.identity.copy(runtime = it)),
            )
            assertEquals(Compatibility.Unknown, result.status, it)
            assertTrue(CompatibilityProblem.UnknownRuntimeVersion in result.problems)
        }
    }

    @Test
    fun `hard failures dominate missing facts and a previously verified stamp`() {
        val failures = listOf(
            evidence.copy(identity = evidence.identity.copy(engine = EngineId("other"))) to CompatibilityProblem.Engine,
            evidence.copy(identity = evidence.identity.copy(heartbeat = "hb3")) to CompatibilityProblem.Heartbeat,
            evidence.copy(
                identity = evidence.identity.copy(connector = "connector3"),
            ) to CompatibilityProblem.Connector,
            evidence.copy(isBaselineConfirmed = false) to CompatibilityProblem.BaselineRejected,
            evidence.copy(isProvenanceConfirmed = false) to CompatibilityProblem.ProvenanceRejected,
            evidence.copy(capabilities = setOf("cancel")) to CompatibilityProblem.BaselineCapability,
            evidence.copy(identity = evidence.identity.copy(protocol = 2)) to CompatibilityProblem.Protocol,
            evidence.copy(
                identity = evidence.identity.copy(configurationSchema = 2),
            ) to CompatibilityProblem.ConfigurationSchema,
            evidence.copy(identity = evidence.identity.copy(historySchema = 2)) to CompatibilityProblem.HistorySchema,
            evidence.copy(
                identity = evidence.identity.copy(nativeTarget = "win32-x64"),
            ) to CompatibilityProblem.NativeTarget,
        )
        failures.forEach { (facts, problem) ->
            val result = assessEngineCompatibility(requirements, facts)
            assertEquals(Compatibility.Incompatible, result.status)
            assertTrue(problem in result.problems)
            assertFalse(result.isBaselineAllowed)
            assertFalse(result.isAutomaticUpdateAllowed)
        }
        val mixed = assessEngineCompatibility(
            requirements,
            evidence.copy(identity = EngineCompatibilityIdentity(), isBaselineConfirmed = false),
        )
        assertEquals(Compatibility.Incompatible, mixed.status)
        assertTrue(CompatibilityProblem.MissingIdentity in mixed.problems)
    }

    @Test
    fun `range boundaries and semantic denylist cannot be bypassed by build metadata`() {
        val bounded = requirements.copy(maximumRuntimeExclusive = "2.0.0", deniedRuntimeVersions = setOf("1.5.0"))
        listOf("0.9.9", "2.0.0", "2.0.0+build", "3.0.0").forEach {
            val result = assessEngineCompatibility(
                bounded,
                evidence.copy(identity = evidence.identity.copy(runtime = it)),
            )
            assertEquals(Compatibility.Incompatible, result.status, it)
            assertTrue(CompatibilityProblem.RuntimeRange in result.problems)
        }
        listOf("1.5.0", "1.5.0+rebuilt").forEach {
            val result = assessEngineCompatibility(
                bounded,
                evidence.copy(identity = evidence.identity.copy(runtime = it)),
            )
            assertTrue(CompatibilityProblem.DeniedRuntime in result.problems)
        }
        assertEquals(
            Compatibility.Unverified,
            assessEngineCompatibility(
                bounded,
                evidence.copy(identity = evidence.identity.copy(runtime = "1.0.0")),
            ).status,
        )
    }

    @Test
    fun `prerelease precedence and large numeric components are not truncated`() {
        val prerelease = requirements.copy(minimumRuntime = "1.0.0-alpha.2", maximumRuntimeExclusive = "1.0.0")
        listOf("1.0.0-alpha.10", "1.0.0-beta", "1.0.0-rc.999999999999999999999999999").forEach {
            assertEquals(
                Compatibility.Unverified,
                assessEngineCompatibility(
                    prerelease,
                    evidence.copy(identity = evidence.identity.copy(runtime = it)),
                ).status,
                it,
            )
        }
        listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-1", "1.0.0").forEach {
            assertEquals(
                Compatibility.Incompatible,
                assessEngineCompatibility(
                    prerelease,
                    evidence.copy(identity = evidence.identity.copy(runtime = it)),
                ).status,
                it,
            )
        }
        assertEquals(
            Compatibility.Unverified,
            assessEngineCompatibility(
                requirements,
                evidence.copy(identity = evidence.identity.copy(runtime = "999999999999999999999999.0.0")),
            ).status,
        )
    }

    @Test
    fun `invalid policy cannot silently become an open range`() {
        assertFailsWith<IllegalArgumentException> { requirements.copy(minimumRuntime = "latest") }
        assertFailsWith<IllegalArgumentException> { requirements.copy(maximumRuntimeExclusive = "bad") }
        assertFailsWith<IllegalArgumentException> { requirements.copy(maximumRuntimeExclusive = "1.0.0") }
        assertFailsWith<IllegalArgumentException> { requirements.copy(protocols = emptySet()) }
        assertFailsWith<IllegalArgumentException> { requirements.copy(deniedRuntimeVersions = setOf("bad")) }
    }

    @Test
    fun `partial identity retains a known protocol rejection without inventing a fingerprint`() {
        val partial = evidence.copy(identity = EngineCompatibilityIdentity(protocol = 999))
        val result = assessEngineCompatibility(requirements, partial)
        assertEquals(Compatibility.Incompatible, result.status)
        assertTrue(CompatibilityProblem.Protocol in result.problems)
        assertTrue(CompatibilityProblem.MissingIdentity in result.problems)
        assertEquals(null, result.stamp)
        listOf(null, "").forEach {
            val absent = assessEngineCompatibility(
                requirements,
                evidence.copy(identity = evidence.identity.copy(nativeTarget = it)),
            )
            assertEquals(Compatibility.Unknown, absent.status)
            assertFalse(CompatibilityProblem.NativeTarget in absent.problems)
        }
    }

    @Test
    fun `a manually constructed decision cannot grant incomplete admission`() {
        assertFailsWith<IllegalArgumentException> { EngineCompatibilityDecision(Compatibility.Verified) }
        assertFailsWith<IllegalArgumentException> {
            EngineCompatibilityDecision(Compatibility.Unverified, setOf(CompatibilityProblem.MissingBaseline), stamp)
        }
        assertFailsWith<IllegalArgumentException> { EngineCompatibilityDecision(Compatibility.Unknown) }
    }

    @Test
    fun `diagnostics omit untrusted identity strings`() {
        val privateStamp = stamp.copy(runtime = "private-runtime", runtimeFingerprint = "private-file")
        val result = assessEngineCompatibility(requirements, evidence.copy(identity = privateStamp.identity()))
        listOf(
            privateStamp.toString(),
            result.toString(),
            evidence.copy(identity = privateStamp.identity()).toString(),
        ).forEach {
            assertFalse("private-runtime" in it)
            assertFalse("private-file" in it)
        }
    }
}
