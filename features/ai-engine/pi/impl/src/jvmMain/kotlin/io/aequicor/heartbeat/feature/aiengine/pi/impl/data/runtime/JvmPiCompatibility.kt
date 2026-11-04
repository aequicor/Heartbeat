package io.aequicor.heartbeat.feature.aiengine.pi.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeDistribution
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiRuntimeRelease
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.generated.PiBuildContract

/**
 * Declared identity of the Pi contracts this connector implements, built from the generated facts of the running
 * build and the pinned distribution instead of a guessed version. [WIRE_REVISION], [PiConfigurationFormat] and
 * [PiHistoryFormat] identify real wire/stored layouts; an incompatible change needs a new revision plus a researched
 * migration. Only desktop JVM targets run a Pi process, so no other target receives these facts.
 */
internal object PiJvmCompatibility {
    /** JSONL RPC wire/DTO revision implemented by this adapter; bump on any incompatible wire change. */
    const val WIRE_REVISION: String = "1"

    /** The selected Pi process starts and answers the offline RPC catalog command. */
    const val RPC_LAUNCH: String = "rpc.launch"

    /** The offline catalog command returns Pi's `models` array. */
    const val CATALOG_MODELS: String = "catalog.models"

    /** Every capability the socket-free baseline has to confirm before an ordinary runtime may start. */
    val baselineCapabilities: Set<String> = setOf(RPC_LAUNCH, CATALOG_MODELS)

    /** Desktop hosts run a Pi process; mobile keeps its explicit unsupported path. */
    val desktopTargets: Set<String> = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)

    /**
     * No Pi peer negotiates a protocol version: this identifies the pinned JSONL schema the baseline probes.
     * [WIRE_REVISION] changes only for an incompatible wire change, never for a Pi release.
     */
    val protocol: EngineProtocolIdentity = EngineProtocolIdentity.ProbedSchema("pi-rpc", WIRE_REVISION)

    /**
     * Policy of this build for the pinned distribution [release]. The runtime is the distribution catalog pin, the
     * protocol/configuration/history are declared revisions, and only the exact desktop target of that pin is
     * accepted. `verified` stays empty: a passing baseline admits the build as Unverified and never authorizes an
     * automatic Managed update.
     */
    fun requirements(release: PiRuntimeRelease?, host: HostPlatform): EngineCompatibilityRequirements? {
        if (release == null || host.name !in desktopTargets) return null
        return EngineCompatibilityRequirements(
            engine = PiEngineId,
            heartbeatVersions = setOf(PiBuildContract.heartbeat),
            connectorVersions = setOf(PiBuildContract.connector),
            minimumRuntime = PiBuildContract.runtimeVersion,
            protocols = setOf(protocol),
            configurationSchemas = setOf(PiConfigurationFormat.CURRENT_REVISION),
            historySchemas = setOf(PiHistoryFormat.CURRENT_REVISION),
            nativeTargets = setOf(release.target),
            baselineCapabilities = baselineCapabilities,
        )
    }

    /**
     * Evidence of one restricted acquisition of [artifact]. Confirmed rows prove the recorded capabilities; a
     * conclusive failure rejects the baseline, and anything unobserved stays inconclusive instead of becoming a pass.
     * Provenance is the pinned archive digest or the verified managed copy's recorded digest, never another artifact.
     */
    fun evidence(
        artifact: PiArtifactFacts,
        baseline: PiBaselineReceipt?,
        isProvenanceConfirmed: Boolean?,
    ): EngineCompatibilityEvidence {
        val confirmed = buildSet {
            if (baseline?.launch == PiBaselineOutcome.Confirmed) add(RPC_LAUNCH)
            if (baseline?.catalog == PiBaselineOutcome.Confirmed) add(CATALOG_MODELS)
        }
        val isRejected = baseline?.hasRejectedCheck == true
        val isComplete = confirmed.containsAll(baselineCapabilities)
        return EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = PiEngineId,
                heartbeat = PiBuildContract.heartbeat,
                connector = PiBuildContract.connector,
                runtime = artifact.runtime,
                protocol = protocol,
                configurationSchema = PiConfigurationFormat.CURRENT_REVISION,
                historySchema = PiHistoryFormat.CURRENT_REVISION,
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
internal data class PiArtifactFacts(val runtime: String?, val nativeTarget: String?, val runtimeFingerprint: String?)

/** Binds the JVM policy where the generated build facts and the pinned distribution exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmPiCompatibility(distribution: PiRuntimeDistribution, platform: PlatformInfo) : PiCompatibility {
    private val policy = PiJvmCompatibility.requirements(distribution.release, platform.host)

    override fun requirements(): EngineCompatibilityRequirements? = policy
}
