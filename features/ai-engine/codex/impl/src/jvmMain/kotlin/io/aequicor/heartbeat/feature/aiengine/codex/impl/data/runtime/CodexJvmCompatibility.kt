package io.aequicor.heartbeat.feature.aiengine.codex.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CODEX_APP_SERVER_FLOOR
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexConfigurationFormat
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexHistoryFormat
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.CodexTarget
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.codexReleaseTarget
import io.aequicor.heartbeat.feature.aiengine.codex.impl.data.generated.CodexBuildContract
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity

/**
 * Declared identity of the Codex app-server contracts this connector implements, built from the generated facts
 * of the running build instead of a guessed version. [WIRE_REVISION], [CodexConfigurationFormat] and
 * [CodexHistoryFormat] identify real wire/stored layouts; an incompatible change needs a new revision plus a
 * researched migration. Only desktop JVM targets run a Codex process, so no other target receives these facts.
 */
internal object CodexJvmCompatibility {
    /** App-server JSON-RPC wire/DTO revision implemented by this adapter; bump on any incompatible wire change. */
    const val WIRE_REVISION: String = "1"

    /** The selected app-server process starts and answers `initialize` with its own handshake. */
    const val APP_SERVER_INITIALIZE: String = "app-server.initialize"

    /** The clean-home app-server answers `config/read` with effective config and layers. */
    const val CONFIG_READ: String = "config.read"

    /** Every capability the clean-home offline baseline has to confirm before an ordinary runtime may start. */
    val baselineCapabilities: Set<String> = setOf(APP_SERVER_INITIALIZE, CONFIG_READ)

    /** Desktop hosts run a Codex process; mobile keeps its explicit unsupported path. */
    val desktopTargets: Set<String> = setOf(HostPlatform.MacOs.name, HostPlatform.Windows.name, HostPlatform.Linux.name)

    /**
     * No Codex peer negotiates a protocol version: this identifies the pinned app-server schema the baseline
     * probes. [WIRE_REVISION] changes only for an incompatible app-server schema change, never for a Codex release.
     */
    val protocol: EngineProtocolIdentity = EngineProtocolIdentity.ProbedSchema("codex-app-server", WIRE_REVISION)

    /**
     * Policy of this build for the release target of [host]. The floor is the adapter's documented app-server
     * baseline ([CODEX_APP_SERVER_FLOOR]); the protocol/configuration/history are declared revisions, and only the
     * exact desktop target of the running host is accepted. `verified` stays empty: a passing baseline admits the
     * build as Unverified and never authorizes an automatic Managed update.
     */
    fun requirements(host: HostPlatform, target: CodexTarget?): EngineCompatibilityRequirements? {
        if (host.name !in desktopTargets || target == null) return null
        return EngineCompatibilityRequirements(
            engine = CodexEngine.Id,
            heartbeatVersions = setOf(CodexBuildContract.heartbeat),
            connectorVersions = setOf(CodexBuildContract.connector),
            minimumRuntime = CODEX_APP_SERVER_FLOOR,
            protocols = setOf(protocol),
            configurationSchemas = setOf(CodexConfigurationFormat.CURRENT_REVISION),
            historySchemas = setOf(CodexHistoryFormat.CURRENT_REVISION),
            nativeTargets = setOf(target.triple),
            baselineCapabilities = baselineCapabilities,
        )
    }

    /**
     * Evidence of one restricted acquisition of [artifact]. Confirmed rows prove the recorded capabilities; a
     * conclusive failure rejects the baseline, and anything unobserved stays inconclusive instead of becoming a
     * pass. Provenance is the managed copy's recorded executable digest, never the release archive digest or a
     * mutable path. The runtime version is the one the probed app-server reported in its own handshake.
     */
    fun evidence(
        artifact: CodexArtifactFacts,
        baseline: CodexBaselineReceipt?,
        isProvenanceConfirmed: Boolean?,
    ): EngineCompatibilityEvidence {
        val confirmed = buildSet {
            if (baseline?.initialize == CodexBaselineOutcome.Confirmed) add(APP_SERVER_INITIALIZE)
            if (baseline?.configRead == CodexBaselineOutcome.Confirmed) add(CONFIG_READ)
        }
        val isRejected = baseline?.hasRejectedCheck == true
        val isComplete = confirmed.containsAll(baselineCapabilities)
        return EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = CodexEngine.Id,
                heartbeat = CodexBuildContract.heartbeat,
                connector = CodexBuildContract.connector,
                runtime = artifact.runtime,
                protocol = protocol,
                configurationSchema = CodexConfigurationFormat.CURRENT_REVISION,
                historySchema = CodexHistoryFormat.CURRENT_REVISION,
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
internal data class CodexArtifactFacts(val runtime: String?, val nativeTarget: String?, val runtimeFingerprint: String?)

/** Binds the JVM policy where the generated build facts and the host release target exist. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class JvmCodexCompatibility(platform: PlatformInfo) : CodexCompatibility {
    private val policy = CodexJvmCompatibility.requirements(platform.host, codexReleaseTarget())

    override fun requirements(): EngineCompatibilityRequirements? = policy
}
