package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KIMI_RUNTIME_VERSION
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.generated.KimiBuildContract

/**
 * Declared identity of the Kimi ACP contracts this connector implements, built from the generated facts of the
 * running build and the pinned Heartbeat runtime instead of a guessed version. [PROTOCOL] names the ACP v1 the
 * shared client actually negotiates; [KimiConfigurationFormat] and [KimiHistoryFormat] identify real launch and
 * observed-record layouts, so an incompatible change needs a new revision plus a researched migration.
 *
 * Admission covers exactly the restricted `initialize` handshake of the pinned artifact. It never creates, resumes,
 * owns or deletes a native session: the open Kimi issues around an unknown `session/new` outcome and session
 * ownership by working directory stay out of scope and must not be inferred from this policy or its baseline.
 */
internal object KimiJvmCompatibility {
    /** The selected process starts and completes the ACP v1 `initialize` handshake. */
    const val ACP_INITIALIZE: String = "acp.initialize"

    /** The pinned runtime advertises the mandatory `heartbeat.permissionGate` ABI 1 during initialization. */
    const val HEARTBEAT_PERMISSION_GATE: String = "heartbeat.permissionGate.1"

    /** The pinned runtime advertises the `heartbeat.hostedCallBinding` ABI 1 during initialization. */
    const val HEARTBEAT_HOSTED_CALL_BINDING: String = "heartbeat.hostedCallBinding.1"

    /** Every capability the restricted ACP `initialize` baseline has to confirm before an ordinary runtime starts. */
    val baselineCapabilities: Set<String> = setOf(
        ACP_INITIALIZE,
        HEARTBEAT_PERMISSION_GATE,
        HEARTBEAT_HOSTED_CALL_BINDING,
    )

    /** Desktop hosts run the controlled Kimi process; mobile keeps its explicit unsupported path. */
    val desktopTargets: Set<String> = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)

    /** Native targets the public `runtime-kimi-2.4.0-hb7` manifest declares; no other target is prepared. */
    val nativeTargets: Set<String> = setOf("win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64", "linux-x64")

    /**
     * Kimi really negotiates ACP v1: the shared client sends protocolVersion 1 and rejects any other answer, so this
     * is a negotiated identity and never a probed, unversioned schema.
     */
    val PROTOCOL: EngineProtocolIdentity = EngineProtocolIdentity.Negotiated("acp", 1)

    /**
     * Policy of this build on a desktop [host]. The runtime floor is the pinned version the adapter enforces
     * ([KIMI_RUNTIME_VERSION]); there is no invented upper bound. Only the manifest's exact desktop native targets
     * are accepted. `verified` stays empty: a passing baseline admits the build as Unverified and never authorizes
     * an automatic Managed update.
     */
    fun requirements(host: HostPlatform): EngineCompatibilityRequirements? {
        if (host.name !in desktopTargets) return null
        return EngineCompatibilityRequirements(
            engine = KimiEngine.Id,
            heartbeatVersions = setOf(KimiBuildContract.heartbeat),
            connectorVersions = setOf(KimiBuildContract.connector),
            minimumRuntime = KIMI_RUNTIME_VERSION,
            protocols = setOf(PROTOCOL),
            configurationSchemas = setOf(KimiConfigurationFormat.CURRENT_REVISION),
            historySchemas = setOf(KimiHistoryFormat.CURRENT_REVISION),
            nativeTargets = nativeTargets,
            baselineCapabilities = baselineCapabilities,
        )
    }

    /**
     * Evidence of one restricted acquisition of [artifact]. Confirmed rows prove only the capabilities the
     * `initialize` inspect observed; a conclusive failure rejects the baseline, and anything unobserved stays
     * inconclusive instead of becoming a pass. Provenance is the pinned release digest of the selected managed or
     * bundled copy, additionally matched against `ManagedInstall.executableSha256` when the store recorded one.
     */
    fun evidence(
        artifact: KimiArtifactFacts,
        baseline: KimiBaselineReceipt?,
        isProvenanceConfirmed: Boolean?,
    ): EngineCompatibilityEvidence {
        val confirmed = buildSet {
            if (baseline?.initialize == KimiBaselineOutcome.Confirmed) add(ACP_INITIALIZE)
            if (baseline?.permissionGate == KimiBaselineOutcome.Confirmed) add(HEARTBEAT_PERMISSION_GATE)
            if (baseline?.hostedCallBinding == KimiBaselineOutcome.Confirmed) add(HEARTBEAT_HOSTED_CALL_BINDING)
        }
        val isRejected = baseline?.hasRejectedCheck == true
        val isComplete = confirmed.containsAll(baselineCapabilities)
        return EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = KimiEngine.Id,
                heartbeat = KimiBuildContract.heartbeat,
                connector = KimiBuildContract.connector,
                runtime = artifact.runtime,
                protocol = PROTOCOL,
                configurationSchema = KimiConfigurationFormat.CURRENT_REVISION,
                historySchema = KimiHistoryFormat.CURRENT_REVISION,
                nativeTarget = artifact.nativeTarget,
                runtimeFingerprint = artifact.runtimeFingerprint,
            ),
            isBaselineConfirmed = when {
                isRejected -> false
                isComplete -> true
                else -> null
            },
            isProvenanceConfirmed = isProvenanceConfirmed,
            capabilities = if (isComplete) baselineCapabilities else null,
        )
    }
}

/** Non-secret identity of the exact selected artifact; null fields stay unestablished instead of being guessed. */
internal data class KimiArtifactFacts(val runtime: String?, val nativeTarget: String?, val runtimeFingerprint: String?)

/** Binds the JVM policy where the generated build facts and the pinned runtime exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmKimiCompatibility(platform: PlatformInfo) : KimiCompatibility {
    private val policy = KimiJvmCompatibility.requirements(platform.host)

    override fun requirements(): EngineCompatibilityRequirements? = policy
}
