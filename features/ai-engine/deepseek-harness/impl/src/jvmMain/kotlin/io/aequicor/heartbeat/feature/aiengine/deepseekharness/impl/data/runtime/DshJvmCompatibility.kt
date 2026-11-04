package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekHost
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshExternalFingerprint
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DshHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.generated.DshBuildContract
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity

/**
 * Declared identity of the DSH ACP contracts this connector implements, built from the generated facts of the
 * running build and the adapter's own launch contract instead of a guessed version. DSH is an external runtime:
 * Heartbeat neither installs nor pins it, so [EngineCompatibilityIdentity.runtimeFingerprint] is an explicitly
 * marked external fingerprint of the selected launcher (`external:sha256:`), never a pinned release digest and
 * never proof that the launcher's whole execution closure (Electron, ASAR, native resources) is sealed.
 *
 * The runtime floor is the verified DSH line the adapter documents ([MINIMUM_RUNTIME]) as a lower bound only;
 * there is no invented upper bound. [PROTOCOL] names the ACP v1 the shared client actually negotiates, and the
 * configuration/history revisions identify only layouts Heartbeat itself authors for DSH (the process-only
 * launch overlay and its own observed-history records). `verified` stays empty: a passing baseline admits the
 * build as Unverified and never authorizes an automatic Managed update.
 */
internal object DshJvmCompatibility {
    /** The verified DSH line the adapter documents, used as a lower bound only. */
    const val MINIMUM_RUNTIME: String = "0.2.1-rc.5"

    /** The selected process starts and completes the ACP v1 `initialize` handshake. */
    const val ACP_INITIALIZE: String = "acp.initialize"

    /** Every capability the restricted ACP `initialize` baseline really confirms before an ordinary runtime starts. */
    val baselineCapabilities: Set<String> = setOf(ACP_INITIALIZE)

    /** Desktop hosts run the external DSH process; mobile keeps its explicit unsupported path. */
    val desktopTargets: Set<String> = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)

    /** Desktop triples an external DSH installation can run on; no other target is admitted. */
    val nativeTargets: Set<String> = setOf(
        "darwin-arm64",
        "darwin-x64",
        "win32-x64",
        "win32-arm64",
        "linux-x64",
        "linux-arm64",
    )

    /**
     * DSH really negotiates ACP v1: the shared client sends protocolVersion 1 and rejects any other answer, so this
     * is a negotiated identity and never a probed, unversioned schema.
     */
    val PROTOCOL: EngineProtocolIdentity = EngineProtocolIdentity.Negotiated("acp", 1)

    /**
     * Policy of this build on a desktop [host]. Only the generated build facts, the documented runtime floor and
     * the desktop target set are declared; the adapter's own resolved `dsh` launch supplies the observed identity.
     */
    fun requirements(host: HostPlatform): EngineCompatibilityRequirements? {
        if (host.name !in desktopTargets) return null
        return EngineCompatibilityRequirements(
            engine = DeepSeekEngine.Id,
            heartbeatVersions = setOf(DshBuildContract.heartbeat),
            connectorVersions = setOf(DshBuildContract.connector),
            minimumRuntime = MINIMUM_RUNTIME,
            protocols = setOf(PROTOCOL),
            configurationSchemas = setOf(DshConfigurationFormat.CURRENT_REVISION),
            historySchemas = setOf(DshHistoryFormat.CURRENT_REVISION),
            nativeTargets = nativeTargets,
            baselineCapabilities = baselineCapabilities,
        )
    }

    /**
     * Evidence of one restricted acquisition of [artifact]. Confirmed rows prove only the capabilities the
     * restricted ACP `initialize` inspect observed; a conclusive failure rejects the baseline, and anything
     * unobserved stays inconclusive instead of becoming a pass. Provenance is confirmed **only** for the
     * explicitly marked external fingerprint of the selected executable; a raw digest or an unmarked value is never
     * promoted to confirmed provenance.
     */
    fun evidence(
        artifact: DshArtifactFacts,
        baseline: DshBaselineReceipt?,
        isProvenanceConfirmed: Boolean?,
    ): EngineCompatibilityEvidence {
        val isConfirmed = baseline?.initialize == DshBaselineOutcome.Confirmed
        val isRejected = baseline?.hasRejectedCheck == true
        val isExternalFingerprint = DshExternalFingerprint.isExternal(artifact.runtimeFingerprint)
        val isConfirmedProvenance = when {
            isProvenanceConfirmed == false -> false
            isProvenanceConfirmed == true && isExternalFingerprint -> true
            else -> null
        }
        return EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = DeepSeekEngine.Id,
                heartbeat = DshBuildContract.heartbeat,
                connector = DshBuildContract.connector,
                runtime = artifact.runtime,
                protocol = PROTOCOL,
                configurationSchema = DshConfigurationFormat.CURRENT_REVISION,
                historySchema = DshHistoryFormat.CURRENT_REVISION,
                nativeTarget = artifact.nativeTarget,
                runtimeFingerprint = artifact.runtimeFingerprint,
            ),
            isBaselineConfirmed = when {
                isRejected -> false
                isConfirmed -> true
                else -> null
            },
            isProvenanceConfirmed = isConfirmedProvenance,
            capabilities = if (isConfirmed) baselineCapabilities else null,
        )
    }
}

/** Non-secret identity of the exact selected executable; null fields stay unestablished instead of being guessed. */
internal data class DshArtifactFacts(val runtime: String?, val nativeTarget: String?, val runtimeFingerprint: String?)

/**
 * Desktop triple of the host that runs the external DSH executable. This observes the platform, not a pinned
 * artifact target; an unknown OS or architecture leaves the identity unestablished.
 */
internal fun dshNativeTarget(host: DeepSeekHost = DeepSeekHost()): String? {
    val arch = when (host.osArch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64", "x64" -> "x64"
        else -> return null
    }
    return when {
        host.osName.startsWith("Mac") -> "darwin-$arch"
        host.isWindows -> "win32-$arch"
        host.osName.startsWith("Linux") -> "linux-$arch"
        else -> null
    }
}

/** Binds the JVM policy where the generated build facts and the desktop host exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmDshCompatibility(platform: PlatformInfo) : DshCompatibility {
    private val policy = DshJvmCompatibility.requirements(platform.host)

    override fun requirements(): EngineCompatibilityRequirements? = policy
}
